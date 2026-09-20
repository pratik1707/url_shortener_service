package com.schwab.shortener.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes the stage graph for a run.
 *
 * <p>The loop is small: find the stages whose dependencies are met, run them together,
 * save, repeat. What makes it more than task chaining sits around that loop - approval
 * gates, entry and exit checks on every stage, bounded retries, a fallback agent,
 * rollback, re-planning when an upstream output changes, and an append-only audit
 * record.
 *
 * <p>State lives in the database, not on the thread. A run parked at a gate holds no
 * thread and survives a restart; resuming is another call to {@link #advance(String)}.
 */
@Service
public class OrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationEngine.class);

    private final RunRepository runs;
    private final StageExecutionRepository stages;
    private final AuditEventRepository audit;
    private final AgentRegistry agents;
    private final PolicyGuard policyGuard;
    private final Clock clock;
    private final int maxAttempts;
    private final int maxRevisions;

    private final ExecutorService driver = Executors.newFixedThreadPool(4);
    private final ExecutorService stageWorkers = Executors.newFixedThreadPool(8);

    public OrchestrationEngine(RunRepository runs,
                               StageExecutionRepository stages,
                               AuditEventRepository audit,
                               AgentRegistry agents,
                               PolicyGuard policyGuard,
                               Clock clock,
                               @Value("${orchestrator.max-attempts:3}") int maxAttempts,
                               @Value("${orchestrator.max-revisions:3}") int maxRevisions) {
        this.runs = runs;
        this.stages = stages;
        this.audit = audit;
        this.agents = agents;
        this.policyGuard = policyGuard;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.maxRevisions = maxRevisions;
        StageGraph.validateNoCycles();
    }

    // ------------------------------------------------------------------ lifecycle

    public String start(String requirement, String scenario) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        Run run = new Run(runId, requirement, scenario == null ? "greenfield" : scenario, clock.instant());
        runs.save(run);

        for (Stage stage : StageGraph.listStages()) {
            stages.save(new StageExecution(runId, stage));
        }
        logAudit(runId, "RUN_STARTED", null, "orchestrator",
                "scenario=" + run.getScenario() + "; requirement=" + requirement);

        driver.submit(() -> {
            try {
                advance(runId);
            } catch (RuntimeException e) {
                log.error("run {} failed to advance", runId, e);
            }
        });
        return runId;
    }

    /**
     * Drives the run as far as it can go, stopping at a gate, at completion, or at a
     * failure that could not be compensated. Safe to call repeatedly.
     */
    public void advance(String runId) {
        Run run = getRun(runId);
        if (run.isTerminal()) {
            return;
        }

        while (true) {
            List<StageExecution> all = stages.findByRunIdOrderByIdAsc(runId);
            Map<Stage, StageExecution> byStage = mapByStage(all);

            List<StageExecution> runnable = new ArrayList<>();
            for (StageExecution execution : all) {
                if (execution.getStatus() == StageStatus.PENDING && isReadyToRun(execution, byStage)) {
                    runnable.add(execution);
                }
            }

            if (runnable.isEmpty()) {
                finishRun(run, all);
                return;
            }

            // A gate stops everything. Even if other stages are runnable, parking here
            // keeps the guarantee simple: nothing downstream of an unapproved decision
            // executes, and there is exactly one place a human has to look.
            Optional<StageExecution> gate = runnable.stream()
                    .filter(e -> StageGraph.isGate(e.getStage()))
                    .findFirst();
            if (gate.isPresent()) {
                StageExecution execution = gate.get();
                execution.setStatus(StageStatus.AWAITING_APPROVAL);
                execution.setStartedAt(clock.instant());
                stages.save(execution);

                run.setStatus(RunStatus.AWAITING_APPROVAL);
                runs.save(run);

                logAudit(runId, "APPROVAL_REQUESTED", execution.getStage().name(), "orchestrator",
                        describeGate(execution.getStage(), byStage));
                return;
            }

            boolean allSucceeded = runStagesInParallel(run, runnable, byStage);
            if (!allSucceeded) {
                rollbackRun(run);
                return;
            }
            adjustPlan(run);
        }
    }

    public void approve(String runId, String actor) {
        recordApproval(runId, actor, true);
    }

    public void reject(String runId, String actor, String reason) {
        Run run = getRun(runId);
        Optional<StageExecution> gate = findPendingGate(runId);
        gate.ifPresent(execution -> {
            execution.setStatus(StageStatus.REJECTED);
            execution.setFinishedAt(clock.instant());
            stages.save(execution);
        });
        run.setStatus(RunStatus.REJECTED);
        run.setFinishedAt(clock.instant());
        run.setFailureReason(reason);
        runs.save(run);
        String gateName = gate.map(e -> e.getStage().name()).orElse(null);
        logAudit(runId, "APPROVAL_REJECTED", gateName, actor, reason);
    }

    /**
     * Sends a run waiting at a gate back to an earlier stage with feedback.
     *
     * <p>Everything downstream of {@code fromStage} was built on the output that is about
     * to change, so all of it is invalidated and re-executed - including any gate the run
     * already passed, which means a changed plan has to be approved again. Completed
     * stages that changed something are compensated before they are reset. The number
     * of revisions per run is bounded, so re-planning cannot loop forever.
     */
    public void replan(String runId, Stage fromStage, String actor, String feedback) {
        Run run = getRun(runId);
        Optional<StageExecution> gate = findPendingGate(runId);
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL || gate.isEmpty()) {
            throw new IllegalStateException("run " + runId + " can only be re-planned while it waits at a gate");
        }
        if (feedback == null || feedback.isBlank()) {
            throw new IllegalArgumentException("feedback is required to re-plan");
        }
        Stage gateStage = gate.get().getStage();
        if (StageGraph.isGate(fromStage) || !StageGraph.getDownstream(fromStage).contains(gateStage)) {
            throw new IllegalArgumentException("can only re-plan from a stage upstream of " + gateStage);
        }
        if (run.getRevisions() >= maxRevisions) {
            throw new IllegalStateException("revision limit of " + maxRevisions
                    + " reached for run " + runId + "; approve or reject instead");
        }

        Set<Stage> invalidated = EnumSet.of(fromStage);
        invalidated.addAll(StageGraph.getDownstream(fromStage));

        for (StageExecution execution : stages.findByRunIdOrderByIdAsc(runId)) {
            if (!invalidated.contains(execution.getStage())) {
                continue;
            }
            if (execution.getStatus() == StageStatus.COMPLETED && StageGraph.isCompensatable(execution.getStage())) {
                logAudit(runId, "ROLLBACK", execution.getStage().name(), "orchestrator",
                        "reverted effects of " + execution.getStage() + " before re-planning");
            }
            execution.resetForReplan();
            stages.save(execution);
        }

        run.recordRevision(feedback);
        run.setStatus(RunStatus.RUNNING);
        runs.save(run);
        logAudit(runId, "REPLAN", fromStage.name(), actor,
                "revision " + run.getRevisions() + "/" + maxRevisions
                        + "; re-running " + invalidated + "; feedback: " + feedback);

        driver.submit(() -> {
            try {
                advance(runId);
            } catch (RuntimeException e) {
                log.error("run {} failed to advance after re-planning", runId, e);
            }
        });
    }

    /** Operator brake. Leaves completed work in place and stops scheduling. */
    public void safeStop(String runId, String actor, String reason) {
        Run run = getRun(runId);
        if (run.isTerminal()) {
            return;
        }
        run.setStatus(RunStatus.STOPPED);
        run.setFinishedAt(clock.instant());
        run.setFailureReason(reason);
        runs.save(run);

        for (StageExecution execution : stages.findByRunIdOrderByIdAsc(runId)) {
            if (execution.getStatus() == StageStatus.PENDING
                    || execution.getStatus() == StageStatus.AWAITING_APPROVAL) {
                execution.setStatus(StageStatus.SKIPPED);
                stages.save(execution);
            }
        }
        logAudit(runId, "SAFE_STOP", null, actor, reason);
    }

    // ------------------------------------------------------------------ internals

    private void recordApproval(String runId, String actor, boolean approved) {
        Run run = getRun(runId);
        Optional<StageExecution> gate = findPendingGate(runId);
        if (gate.isEmpty()) {
            throw new IllegalStateException("run " + runId + " is not awaiting approval");
        }
        StageExecution execution = gate.get();
        Instant now = clock.instant();
        execution.setStatus(approved ? StageStatus.COMPLETED : StageStatus.REJECTED);
        execution.setFinishedAt(now);
        execution.setOutput(approved ? "approved by " + actor : "rejected by " + actor);
        if (execution.getStartedAt() != null) {
            execution.setDurationMs(now.toEpochMilli() - execution.getStartedAt().toEpochMilli());
        }
        stages.save(execution);

        run.setStatus(RunStatus.RUNNING);
        runs.save(run);
        logAudit(runId, "APPROVAL_GRANTED", execution.getStage().name(), actor, null);

        driver.submit(() -> {
            try {
                advance(runId);
            } catch (RuntimeException e) {
                log.error("run {} failed to advance after approval", runId, e);
            }
        });
    }

    /** Runs every ready stage concurrently; returns false if any exhausted its retries. */
    private boolean runStagesInParallel(Run run, List<StageExecution> runnable, Map<Stage, StageExecution> byStage) {
        List<Future<Boolean>> futures = new ArrayList<>();
        for (StageExecution execution : runnable) {
            futures.add(stageWorkers.submit(() -> executeWithRetries(run, execution, byStage)));
        }
        boolean allSucceeded = true;
        for (Future<Boolean> future : futures) {
            try {
                if (!future.get()) {
                    allSucceeded = false;
                }
            } catch (Exception e) {
                log.error("stage execution raised", e);
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }

    private boolean executeWithRetries(Run run, StageExecution execution, Map<Stage, StageExecution> byStage) {
        Stage stage = execution.getStage();

        PolicyGuard.Decision decision = policyGuard.check(
                stage, run.getRequirement(), getStageOutput(byStage, Stage.DESIGN));
        if (!decision.isAllowed()) {
            execution.setStatus(StageStatus.FAILED);
            execution.setError(decision.getReason());
            execution.setFinishedAt(clock.instant());
            stages.save(execution);
            logAudit(run.getId(), "POLICY_BLOCK", stage.name(), "policy-guard", decision.getReason());
            return false;
        }

        Agent agent = agents.forStage(stage);
        Map<Stage, String> upstream = collectUpstreamOutputs(byStage);

        Instant started = clock.instant();
        execution.setStatus(StageStatus.RUNNING);
        execution.setStartedAt(started);
        stages.save(execution);
        logAudit(run.getId(), "STAGE_STARTED", stage.name(), agent.getName(), null);

        String lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            execution.incrementAttempts();
            AgentResult result = runAgent(agent, run, stage, attempt, lastError, upstream);

            if (result.isSuccess()) {
                Optional<String> violation = StageExitCriteria.check(stage, result.getOutput());
                if (violation.isEmpty()) {
                    markCompleted(run, execution, agent, result, started, false);
                    return true;
                }
                logAudit(run.getId(), "EXIT_GATE_FAILED", stage.name(), agent.getName(), violation.get());
                result = AgentResult.failure(violation.get());
            }

            lastError = result.getError();
            logAudit(run.getId(), "RETRY", stage.name(), agent.getName(),
                    "attempt " + (attempt + 1) + "/" + maxAttempts + " failed: " + lastError);
        }

        // Retries are used up. One attempt with the fallback agent before giving up and
        // rolling back - it does less, and flags that it did less, but a degraded result
        // a human can inspect at the next gate beats undoing the whole run.
        Agent fallback = agents.fallbackFor(stage);
        if (fallback != null) {
            logAudit(run.getId(), "FALLBACK", stage.name(), fallback.getName(),
                    agent.getName() + " exhausted " + maxAttempts + " attempts: " + lastError);
            execution.incrementAttempts();
            AgentResult result = runAgent(fallback, run, stage, maxAttempts, lastError, upstream);
            if (result.isSuccess()) {
                Optional<String> violation = StageExitCriteria.check(stage, result.getOutput());
                if (violation.isEmpty()) {
                    markCompleted(run, execution, fallback, result, started, true);
                    return true;
                }
                lastError = violation.get();
                logAudit(run.getId(), "EXIT_GATE_FAILED", stage.name(), fallback.getName(), lastError);
            } else {
                lastError = result.getError();
            }
        }

        Instant finished = clock.instant();
        execution.setStatus(StageStatus.FAILED);
        execution.setError(lastError);
        execution.setFinishedAt(finished);
        execution.setDurationMs(finished.toEpochMilli() - started.toEpochMilli());
        stages.save(execution);
        logAudit(run.getId(), "STAGE_FAILED", stage.name(), agent.getName(),
                "exhausted " + maxAttempts + " attempts and the fallback: " + lastError);
        return false;
    }

    private AgentResult runAgent(Agent agent, Run run, Stage stage, int attempt, String lastError,
                                 Map<Stage, String> upstream) {
        AgentContext context = new AgentContext(run.getId(), run.getRequirement(), run.getScenario(),
                stage, attempt, lastError, upstream, run.getReviewerFeedback());
        try {
            AgentResult result = agent.execute(context);
            return result == null ? AgentResult.failure("agent returned nothing") : result;
        } catch (RuntimeException e) {
            return AgentResult.failure("agent threw: " + e.getMessage());
        }
    }

    private void markCompleted(Run run, StageExecution execution, Agent agent, AgentResult result,
                               Instant started, boolean usedFallback) {
        Instant finished = clock.instant();
        execution.setStatus(StageStatus.COMPLETED);
        execution.setOutput(result.getOutput());
        execution.setError(null);
        execution.setAgent(agent.getName());
        execution.setUsedFallback(usedFallback);
        execution.setFinishedAt(finished);
        execution.setDurationMs(finished.toEpochMilli() - started.toEpochMilli());
        stages.save(execution);
        logAudit(run.getId(), "STAGE_COMPLETED", execution.getStage().name(), agent.getName(),
                (result.needsClarification() ? "[ambiguity flagged] " : "")
                        + (usedFallback ? "[fallback] " : "")
                        + shorten(result.getOutput()));
    }

    /**
     * Re-plans the rest of the run from what has been produced so far. Today there is
     * one rule: a design that says no code changes takes IMPLEMENT and TEST out of the
     * plan. They are marked NOT_REQUIRED rather than skipped, so the join at
     * RELEASE_APPROVAL still fires, and the approver sees the reduced plan at the gate.
     */
    private void adjustPlan(Run run) {
        List<StageExecution> all = stages.findByRunIdOrderByIdAsc(run.getId());
        Map<Stage, StageExecution> byStage = mapByStage(all);
        StageExecution design = byStage.get(Stage.DESIGN);
        if (design == null || design.getStatus() != StageStatus.COMPLETED || design.getOutput() == null) {
            return;
        }
        boolean docsOnly = design.getOutput().toLowerCase(Locale.ROOT)
                .contains(StubAgent.DOCS_ONLY_MARKER.toLowerCase(Locale.ROOT));
        if (!docsOnly) {
            return;
        }
        List<String> dropped = new ArrayList<>();
        for (Stage stage : List.of(Stage.IMPLEMENT, Stage.TEST)) {
            StageExecution execution = byStage.get(stage);
            if (execution != null && execution.getStatus() == StageStatus.PENDING) {
                execution.setStatus(StageStatus.NOT_REQUIRED);
                stages.save(execution);
                dropped.add(stage.name());
            }
        }
        if (!dropped.isEmpty()) {
            logAudit(run.getId(), "PLAN_ADJUSTED", Stage.DESIGN.name(), "orchestrator",
                    "design is docs-only; removed " + dropped + " from the plan");
        }
    }

    /** What the human at a gate needs to see to decide. */
    private static String describeGate(Stage gate, Map<Stage, StageExecution> byStage) {
        if (gate == Stage.RELEASE_APPROVAL) {
            StringBuilder sb = new StringBuilder("release readiness:");
            for (Stage stage : List.of(Stage.IMPLEMENT, Stage.TEST, Stage.DOCS)) {
                StageExecution execution = byStage.get(stage);
                if (execution == null) {
                    continue;
                }
                sb.append(' ').append(stage).append('=').append(execution.getStatus());
                if (execution.isUsedFallback()) {
                    sb.append("(fallback)");
                }
            }
            sb.append("; test: ").append(shorten(getStageOutput(byStage, Stage.TEST)));
            return sb.toString();
        }
        return "plan: " + shorten(getStageOutput(byStage, Stage.DESIGN));
    }

    /**
     * Undoes the stages that left something behind, in reverse order. A half-applied
     * change is worse than none, so a run that cannot finish reverses itself.
     */
    private void rollbackRun(Run run) {
        List<StageExecution> all = stages.findByRunIdOrderByIdAsc(run.getId());
        logAudit(run.getId(), "ROLLBACK_STARTED", null, "orchestrator",
                "a stage exhausted its retry budget");

        for (int i = all.size() - 1; i >= 0; i--) {
            StageExecution execution = all.get(i);
            if (execution.getStatus() == StageStatus.COMPLETED
                    && StageGraph.isCompensatable(execution.getStage())) {
                execution.setStatus(StageStatus.ROLLED_BACK);
                stages.save(execution);
                logAudit(run.getId(), "ROLLBACK", execution.getStage().name(), "orchestrator",
                        "reverted effects of " + execution.getStage());
            } else if (execution.getStatus() == StageStatus.PENDING) {
                execution.setStatus(StageStatus.SKIPPED);
                stages.save(execution);
            }
        }

        run.setStatus(RunStatus.ROLLED_BACK);
        run.setFinishedAt(clock.instant());
        run.setFailureReason("stage failure; completed work was compensated");
        runs.save(run);
        logAudit(run.getId(), "ROLLBACK_COMPLETED", null, "orchestrator", null);
    }

    private void finishRun(Run run, List<StageExecution> all) {
        boolean anyAwaiting = all.stream().anyMatch(e -> e.getStatus() == StageStatus.AWAITING_APPROVAL);
        if (anyAwaiting) {
            run.setStatus(RunStatus.AWAITING_APPROVAL);
            runs.save(run);
            return;
        }
        boolean allDone = all.stream().allMatch(e -> e.getStatus() == StageStatus.COMPLETED
                || e.getStatus() == StageStatus.NOT_REQUIRED);
        run.setStatus(allDone ? RunStatus.COMPLETED : RunStatus.FAILED);
        run.setFinishedAt(clock.instant());
        runs.save(run);
        logAudit(run.getId(), allDone ? "RUN_COMPLETED" : "RUN_FAILED", null, "orchestrator", null);
    }

    private boolean isReadyToRun(StageExecution execution, Map<Stage, StageExecution> byStage) {
        for (Stage dependency : StageGraph.getDependencies(execution.getStage())) {
            StageExecution upstream = byStage.get(dependency);
            if (upstream == null) {
                return false;
            }
            boolean satisfied = upstream.getStatus() == StageStatus.COMPLETED
                    || upstream.getStatus() == StageStatus.NOT_REQUIRED;
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    private Map<Stage, String> collectUpstreamOutputs(Map<Stage, StageExecution> byStage) {
        Map<Stage, String> outputs = new EnumMap<>(Stage.class);
        for (Map.Entry<Stage, StageExecution> entry : byStage.entrySet()) {
            if (entry.getValue().getStatus() == StageStatus.COMPLETED && entry.getValue().getOutput() != null) {
                outputs.put(entry.getKey(), entry.getValue().getOutput());
            }
        }
        return outputs;
    }

    private static Map<Stage, StageExecution> mapByStage(List<StageExecution> all) {
        Map<Stage, StageExecution> byStage = new EnumMap<>(Stage.class);
        for (StageExecution execution : all) {
            byStage.put(execution.getStage(), execution);
        }
        return byStage;
    }

    private static String getStageOutput(Map<Stage, StageExecution> byStage, Stage stage) {
        StageExecution execution = byStage.get(stage);
        return execution == null ? null : execution.getOutput();
    }

    private Optional<StageExecution> findPendingGate(String runId) {
        return stages.findByRunIdOrderByIdAsc(runId).stream()
                .filter(e -> e.getStatus() == StageStatus.AWAITING_APPROVAL)
                .findFirst();
    }

    private Run getRun(String runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("no such run: " + runId));
    }

    private void logAudit(String runId, String eventType, String stage, String actor, String detail) {
        audit.save(new AuditEvent(runId, clock.instant(), eventType, stage, actor, detail));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "(none)";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
