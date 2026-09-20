package com.schwab.shortener.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the orchestrator itself: does it stop where it should, retry when it
 * should, and undo what it did when it cannot finish.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrchestrationEngineTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private RunRepository runs;

    @Autowired
    private StageExecutionRepository stages;

    @Autowired
    private AuditEventRepository audit;

    @Test
    @DisplayName("a new run stops at the approval gate and waits for a person")
    void runStopsAtTheApprovalGate() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitUntil(() -> statusOf(runId) == RunStatus.AWAITING_APPROVAL);

        assertEquals(RunStatus.AWAITING_APPROVAL, statusOf(runId));

        Map<Stage, StageStatus> byStage = stageStatuses(runId);
        assertEquals(StageStatus.COMPLETED, byStage.get(Stage.REQUIREMENTS));
        assertEquals(StageStatus.COMPLETED, byStage.get(Stage.DESIGN));
        assertEquals(StageStatus.AWAITING_APPROVAL, byStage.get(Stage.APPROVAL));

        // Nothing past the gate may have started.
        assertEquals(StageStatus.PENDING, byStage.get(Stage.IMPLEMENT));
        assertEquals(StageStatus.PENDING, byStage.get(Stage.DOCS));
        assertEquals(StageStatus.PENDING, byStage.get(Stage.RELEASE));
    }

    @Test
    @DisplayName("after the plan is approved, the run stops again before release")
    void runStopsAgainBeforeRelease() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitForGate(runId, Stage.APPROVAL);
        engine.approve(runId, "pratik");
        waitForGate(runId, Stage.RELEASE_APPROVAL);

        Map<Stage, StageStatus> byStage = stageStatuses(runId);
        assertEquals(StageStatus.COMPLETED, byStage.get(Stage.IMPLEMENT));
        assertEquals(StageStatus.COMPLETED, byStage.get(Stage.TEST));
        assertEquals(StageStatus.COMPLETED, byStage.get(Stage.DOCS));
        assertEquals(StageStatus.PENDING, byStage.get(Stage.RELEASE), "release must wait for the second approval");
    }

    @Test
    @DisplayName("approving both gates lets the run finish")
    void approvingLetsTheRunFinish() {
        String runId = engine.start("add a health endpoint", "greenfield");
        approveBothGates(runId);

        assertEquals(RunStatus.COMPLETED, statusOf(runId));
        stageStatuses(runId).forEach((stage, status) ->
                assertEquals(StageStatus.COMPLETED, status, stage + " should have completed"));
    }

    @Test
    @DisplayName("rejecting stops the run and nothing downstream runs")
    void rejectingStopsTheRun() {
        String runId = engine.start("delete all the things", "greenfield");
        waitUntil(() -> statusOf(runId) == RunStatus.AWAITING_APPROVAL);

        engine.reject(runId, "pratik", "not this quarter");

        assertEquals(RunStatus.REJECTED, statusOf(runId));
        Map<Stage, StageStatus> byStage = stageStatuses(runId);
        assertEquals(StageStatus.REJECTED, byStage.get(Stage.APPROVAL));
        assertEquals(StageStatus.PENDING, byStage.get(Stage.IMPLEMENT));
    }

    /**
     * The stub test agent fails its first attempt when the requirement mentions
     * "migrate", which lets us watch the retry happen for real.
     */
    @Test
    @DisplayName("a failing stage is retried and can still succeed")
    void failingStageIsRetriedAndRecovers() {
        String runId = engine.start("migrate code generation to the counter", "brownfield");
        approveBothGates(runId);

        StageExecution test = stageFor(runId, Stage.TEST);
        assertEquals(StageStatus.COMPLETED, test.getStatus());
        assertTrue(test.getAttempts() >= 2, "expected at least one retry, saw " + test.getAttempts());

        assertTrue(auditTypes(runId).contains("RETRY"), "the retry should be in the audit trail");
    }

    @Test
    @DisplayName("every step and every human decision lands in the audit trail")
    void everythingIsAudited() {
        String runId = engine.start("add a health endpoint", "greenfield");
        approveBothGates(runId);

        List<String> types = auditTypes(runId);
        assertTrue(types.contains("RUN_STARTED"));
        assertTrue(types.contains("APPROVAL_REQUESTED"));
        assertTrue(types.contains("APPROVAL_GRANTED"));
        assertTrue(types.contains("STAGE_COMPLETED"));
        assertTrue(types.contains("RUN_COMPLETED"));

        boolean approverRecorded = audit.findByRunIdOrderByIdAsc(runId).stream()
                .anyMatch(e -> "APPROVAL_GRANTED".equals(e.getEventType()) && "pratik".equals(e.getActor()));
        assertTrue(approverRecorded, "the audit trail should name who approved");
    }

    @Test
    @DisplayName("a requirement that trips a policy rule never reaches implementation")
    void policyRuleBlocksTheRun() {
        String runId = engine.start("drop database shortener and rebuild it", "greenfield");
        waitUntil(() -> {
            RunStatus status = statusOf(runId);
            return status == RunStatus.ROLLED_BACK || status == RunStatus.FAILED;
        });

        assertTrue(auditTypes(runId).contains("POLICY_BLOCK"));
        // The run was rolled back before reaching implementation, so that stage was
        // skipped rather than executed.
        assertEquals(StageStatus.SKIPPED, stageFor(runId, Stage.IMPLEMENT).getStatus());
    }

    @Test
    @DisplayName("stopping a waiting run leaves finished work alone and schedules nothing more")
    void safeStopHaltsTheRun() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitUntil(() -> statusOf(runId) == RunStatus.AWAITING_APPROVAL);

        engine.safeStop(runId, "pratik", "pausing for the release freeze");

        assertEquals(RunStatus.STOPPED, statusOf(runId));
        assertEquals(StageStatus.COMPLETED, stageFor(runId, Stage.REQUIREMENTS).getStatus());
        assertEquals(StageStatus.SKIPPED, stageFor(runId, Stage.IMPLEMENT).getStatus());
        assertTrue(auditTypes(runId).contains("SAFE_STOP"));
    }

    @Test
    @DisplayName("each stage can read what the stages before it produced")
    void stagesSeeUpstreamOutput() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitUntil(() -> statusOf(runId) == RunStatus.AWAITING_APPROVAL);

        String design = stageFor(runId, Stage.DESIGN).getOutput();
        assertNotNull(design);
        assertTrue(design.contains("Upstream:"), "design should quote what requirements concluded");
    }

    @Test
    @DisplayName("when the primary agent keeps failing, the fallback agent finishes the stage and says so")
    void fallbackTakesOverWhenRetriesRunOut() {
        String runId = engine.start("fix the flaky redirect test", "brownfield");
        waitForGate(runId, Stage.APPROVAL);
        engine.approve(runId, "pratik");
        waitForGate(runId, Stage.RELEASE_APPROVAL);

        StageExecution test = stageFor(runId, Stage.TEST);
        assertEquals(StageStatus.COMPLETED, test.getStatus());
        assertTrue(test.isUsedFallback(), "the fallback should have produced the test result");
        assertTrue(test.getOutput().contains("FLAGGED"), "a fallback result must say it is one");
        assertTrue(auditTypes(runId).contains("FALLBACK"));
    }

    @Test
    @DisplayName("feedback at the gate re-runs the design and stops at the gate again")
    void reviewerFeedbackReplansTheDesign() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitForGate(runId, Stage.APPROVAL);

        engine.replan(runId, Stage.DESIGN, "pratik", "also report the counter block size");
        waitForGate(runId, Stage.APPROVAL);

        assertTrue(stageFor(runId, Stage.DESIGN).getOutput().contains("also report the counter block size"),
                "the new design should address the feedback");
        assertEquals(1, runs.findById(runId).orElseThrow().getRevisions());
        assertTrue(auditTypes(runId).contains("REPLAN"));
    }

    @Test
    @DisplayName("an ambiguous requirement is clarified by re-planning from requirements")
    void ambiguityIsResolvedByReplanning() {
        String runId = engine.start("links should expire", "ambiguous");
        waitForGate(runId, Stage.APPROVAL);
        assertTrue(stageFor(runId, Stage.REQUIREMENTS).getOutput().contains("AMBIGUITY"));

        engine.replan(runId, Stage.REQUIREMENTS, "pratik", "expire 30 days after creation by default");
        waitForGate(runId, Stage.APPROVAL);

        String requirements = stageFor(runId, Stage.REQUIREMENTS).getOutput();
        assertFalse(requirements.contains("AMBIGUITY:"), "the clarified requirement should not be ambiguous");
        assertTrue(requirements.contains("30 days"));
    }

    @Test
    @DisplayName("re-planning after release was prepared compensates implementation and needs the plan approved again")
    void replanningAtReleaseGoesBackThroughTheFirstGate() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitForGate(runId, Stage.APPROVAL);
        engine.approve(runId, "pratik");
        waitForGate(runId, Stage.RELEASE_APPROVAL);

        engine.replan(runId, Stage.DESIGN, "pratik", "use a separate controller");
        waitForGate(runId, Stage.APPROVAL);

        assertEquals(StageStatus.PENDING, stageFor(runId, Stage.IMPLEMENT).getStatus());
        boolean compensated = audit.findByRunIdOrderByIdAsc(runId).stream()
                .anyMatch(e -> "ROLLBACK".equals(e.getEventType()) && "IMPLEMENT".equals(e.getStage()));
        assertTrue(compensated, "completed implementation should be reverted before re-planning");
    }

    @Test
    @DisplayName("re-planning is bounded")
    void replanningIsBounded() {
        String runId = engine.start("add a health endpoint", "greenfield");
        waitForGate(runId, Stage.APPROVAL);
        engine.replan(runId, Stage.DESIGN, "pratik", "first change");
        waitForGate(runId, Stage.APPROVAL);
        engine.replan(runId, Stage.DESIGN, "pratik", "second change");
        waitForGate(runId, Stage.APPROVAL);

        // max-revisions is 2 in the test profile.
        assertThrows(IllegalStateException.class,
                () -> engine.replan(runId, Stage.DESIGN, "pratik", "third change"));
    }

    @Test
    @DisplayName("a docs-only design takes implementation and testing out of the plan")
    void docsOnlyDesignShrinksThePlan() {
        String runId = engine.start("update the README to explain the alias rules", "greenfield");
        waitForGate(runId, Stage.APPROVAL);

        assertEquals(StageStatus.NOT_REQUIRED, stageFor(runId, Stage.IMPLEMENT).getStatus());
        assertEquals(StageStatus.NOT_REQUIRED, stageFor(runId, Stage.TEST).getStatus());
        assertTrue(auditTypes(runId).contains("PLAN_ADJUSTED"));

        engine.approve(runId, "pratik");
        waitForGate(runId, Stage.RELEASE_APPROVAL);
        engine.approve(runId, "pratik");
        waitUntil(() -> statusOf(runId) == RunStatus.COMPLETED);
        assertEquals(StageStatus.COMPLETED, stageFor(runId, Stage.RELEASE).getStatus());
    }

    // ------------------------------------------------------------------- helpers

    private void waitForGate(String runId, Stage gate) {
        waitUntil(() -> statusOf(runId) == RunStatus.AWAITING_APPROVAL
                && stageFor(runId, gate).getStatus() == StageStatus.AWAITING_APPROVAL);
    }

    private void approveBothGates(String runId) {
        waitForGate(runId, Stage.APPROVAL);
        engine.approve(runId, "pratik");
        waitForGate(runId, Stage.RELEASE_APPROVAL);
        engine.approve(runId, "pratik");
        waitUntil(() -> statusOf(runId) == RunStatus.COMPLETED);
    }

    private RunStatus statusOf(String runId) {
        return runs.findById(runId).orElseThrow().getStatus();
    }

    private Map<Stage, StageStatus> stageStatuses(String runId) {
        return stages.findByRunIdOrderByIdAsc(runId).stream()
                .collect(Collectors.toMap(StageExecution::getStage, StageExecution::getStatus));
    }

    private StageExecution stageFor(String runId, Stage stage) {
        return stages.findByRunIdOrderByIdAsc(runId).stream()
                .filter(e -> e.getStage() == stage)
                .findFirst()
                .orElseThrow();
    }

    private List<String> auditTypes(String runId) {
        return audit.findByRunIdOrderByIdAsc(runId).stream()
                .map(AuditEvent::getEventType)
                .toList();
    }

    /** Runs are driven on a background thread, so tests wait for the state they expect. */
    private void waitUntil(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting", e);
            }
        }
        throw new AssertionError("timed out waiting for the expected state");
    }
}
