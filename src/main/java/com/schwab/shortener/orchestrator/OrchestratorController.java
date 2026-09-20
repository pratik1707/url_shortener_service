package com.schwab.shortener.orchestrator;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orchestrator")
public class OrchestratorController {

    private final OrchestrationEngine engine;
    private final RunRepository runs;
    private final StageExecutionRepository stages;
    private final AuditEventRepository audit;
    private final OrchestratorMetrics metrics;
    private final AgentRegistry agents;

    public OrchestratorController(OrchestrationEngine engine,
                                  RunRepository runs,
                                  StageExecutionRepository stages,
                                  AuditEventRepository audit,
                                  OrchestratorMetrics metrics,
                                  AgentRegistry agents) {
        this.engine = engine;
        this.runs = runs;
        this.stages = stages;
        this.audit = audit;
        this.metrics = metrics;
        this.agents = agents;
    }

    /** Body of {@code POST /orchestrator/runs}. */
    public record StartRunRequest(String requirement, String scenario) {
    }

    /**
     * Body of the gate decisions. {@code reason} is the rejection reason, the stop reason,
     * or - for {@code /revise} - the feedback. {@code fromStage} is only used by
     * {@code /revise} and defaults to DESIGN.
     */
    public record DecisionRequest(String actor, String reason, String fromStage) {
    }

    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRunRequest request) {
        if (request.requirement() == null || request.requirement().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "requirement is required"));
        }
        String runId = engine.start(request.requirement(), request.scenario());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", RunStatus.RUNNING.name());
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Run run : runs.findAllByOrderByStartedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", run.getId());
            row.put("requirement", run.getRequirement());
            row.put("scenario", run.getScenario());
            row.put("status", run.getStatus().name());
            row.put("startedAt", run.getStartedAt().toString());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("runId") String runId) {
        return runs.findById(runId)
                .<ResponseEntity<Map<String, Object>>>map(run -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("runId", run.getId());
                    body.put("requirement", run.getRequirement());
                    body.put("scenario", run.getScenario());
                    body.put("status", run.getStatus().name());
                    body.put("startedAt", run.getStartedAt().toString());
                    body.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
                    body.put("failureReason", run.getFailureReason());
                    body.put("revisions", run.getRevisions());
                    body.put("reviewerFeedback", run.getReviewerFeedback());
                    body.put("agents", agents.listAgentNames());

                    List<Map<String, Object>> stageRows = new ArrayList<>();
                    for (StageExecution execution : stages.findByRunIdOrderByIdAsc(runId)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("stage", execution.getStage().name());
                        row.put("status", execution.getStatus().name());
                        row.put("attempts", execution.getAttempts());
                        row.put("durationMs", execution.getDurationMs());
                        row.put("output", execution.getOutput());
                        row.put("error", execution.getError());
                        row.put("agent", execution.getAgent());
                        row.put("usedFallback", execution.isUsedFallback());
                        row.put("dependsOn", StageGraph.getDependencies(execution.getStage())
                                .stream().map(Enum::name).toList());
                        stageRows.add(row);
                    }
                    body.put("stages", stageRows);

                    List<Map<String, Object>> auditRows = new ArrayList<>();
                    for (AuditEvent event : audit.findByRunIdOrderByIdAsc(runId)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("at", event.getAt().toString());
                        row.put("eventType", event.getEventType());
                        row.put("stage", event.getStage());
                        row.put("actor", event.getActor());
                        row.put("detail", event.getDetail());
                        auditRows.add(row);
                    }
                    body.put("audit", auditRows);
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "no such run: " + runId)));
    }

    @PostMapping("/runs/{runId}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable("runId") String runId,
                                                       @RequestBody(required = false) DecisionRequest request) {
        String actor = request == null || request.actor() == null ? "operator" : request.actor();
        engine.approve(runId, actor);
        return ResponseEntity.ok(Map.of("runId", runId, "decision", "approved", "actor", actor));
    }

    @PostMapping("/runs/{runId}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable("runId") String runId,
                                                      @RequestBody(required = false) DecisionRequest request) {
        String actor = request == null || request.actor() == null ? "operator" : request.actor();
        String reason = request == null || request.reason() == null ? "rejected at gate" : request.reason();
        engine.reject(runId, actor, reason);
        return ResponseEntity.ok(Map.of("runId", runId, "decision", "rejected", "actor", actor));
    }

    /**
     * Sends a run waiting at a gate back to an earlier stage with feedback. Everything
     * downstream of that stage is re-run, and the run stops at the gate again.
     */
    @PostMapping("/runs/{runId}/revise")
    public ResponseEntity<Map<String, Object>> revise(@PathVariable("runId") String runId,
                                                      @RequestBody(required = false) DecisionRequest request) {
        String actor = request == null || request.actor() == null ? "operator" : request.actor();
        String feedback = request == null ? null : request.reason();
        Stage fromStage = request == null || request.fromStage() == null || request.fromStage().isBlank()
                ? Stage.DESIGN
                : Stage.valueOf(request.fromStage().trim().toUpperCase(java.util.Locale.ROOT));
        engine.replan(runId, fromStage, actor, feedback);
        return ResponseEntity.ok(Map.of("runId", runId, "decision", "revise",
                "fromStage", fromStage.name(), "actor", actor));
    }

    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("runId") String runId,
                                                    @RequestBody(required = false) DecisionRequest request) {
        String actor = request == null || request.actor() == null ? "operator" : request.actor();
        String reason = request == null || request.reason() == null ? "safe-stop requested" : request.reason();
        engine.safeStop(runId, actor, reason);
        return ResponseEntity.ok(Map.of("runId", runId, "decision", "stopped", "actor", actor));
    }

    /** A decision that does not fit the run's current state, e.g. approving a finished run. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", String.valueOf(e.getMessage())));
    }

    /** Bad input, e.g. an unknown stage name or missing feedback. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", String.valueOf(e.getMessage())));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.getMetrics();
    }

    @GetMapping("/graph")
    public List<Map<String, Object>> graph() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Stage stage : StageGraph.listStages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("dependsOn", StageGraph.getDependencies(stage).stream().map(Enum::name).toList());
            row.put("gate", StageGraph.isGate(stage));
            row.put("compensatable", StageGraph.isCompensatable(stage));
            out.add(row);
        }
        return out;
    }
}
