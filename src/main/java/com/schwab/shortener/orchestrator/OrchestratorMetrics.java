package com.schwab.shortener.orchestrator;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reliability metrics computed from the stored runs rather than kept in counters, so
 * they cannot drift from what actually happened and they survive a restart. At a scale
 * where recomputing mattered this becomes a rollup; the source of truth is the same.
 */
@Component
public class OrchestratorMetrics {

    private final RunRepository runs;
    private final StageExecutionRepository stages;

    public OrchestratorMetrics(RunRepository runs, StageExecutionRepository stages) {
        this.runs = runs;
        this.stages = stages;
    }

    public Map<String, Object> getMetrics() {
        List<Run> allRuns = runs.findAll();
        List<StageExecution> allStages = stages.findAll();

        long total = allRuns.size();
        long completed = allRuns.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED).count();
        long rolledBack = allRuns.stream().filter(r -> r.getStatus() == RunStatus.ROLLED_BACK).count();
        long failed = allRuns.stream().filter(r -> r.getStatus() == RunStatus.FAILED).count();
        long awaiting = allRuns.stream().filter(r -> r.getStatus() == RunStatus.AWAITING_APPROVAL).count();

        // Retries beyond the first attempt on every stage.
        long retries = allStages.stream().mapToLong(s -> Math.max(0, s.getAttempts() - 1)).sum();

        // Mean time to recover: how long stages that eventually succeeded after at
        // least one failed attempt took, end to end.
        List<Long> recovered = allStages.stream()
                .filter(s -> s.getStatus() == StageStatus.COMPLETED && s.getAttempts() > 1)
                .map(StageExecution::getDurationMs)
                .filter(d -> d != null)
                .toList();
        double mttr = recovered.isEmpty()
                ? 0.0
                : recovered.stream().mapToLong(Long::longValue).average().orElse(0.0);

        List<Long> endToEnd = allRuns.stream()
                .filter(r -> r.getFinishedAt() != null)
                .map(r -> r.getFinishedAt().toEpochMilli() - r.getStartedAt().toEpochMilli())
                .sorted()
                .toList();

        long replans = allRuns.stream().mapToLong(Run::getRevisions).sum();
        long fallbacks = allStages.stream().filter(StageExecution::isUsedFallback).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runsTotal", total);
        out.put("runsCompleted", completed);
        out.put("runsRolledBack", rolledBack);
        out.put("runsFailed", failed);
        out.put("runsAwaitingApproval", awaiting);
        out.put("successRate", total == 0 ? 0.0 : round((double) completed / total));
        out.put("retryCount", retries);
        out.put("rollbackRate", total == 0 ? 0.0 : round((double) rolledBack / total));
        out.put("fallbackCount", fallbacks);
        out.put("replanCount", replans);
        out.put("mttrMillis", round(mttr));
        out.put("endToEndP50Millis", percentile(endToEnd, 0.50));
        out.put("endToEndP95Millis", percentile(endToEnd, 0.95));
        return out;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
