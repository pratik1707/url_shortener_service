package com.schwab.shortener.orchestrator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a stage knows when it runs: the requirement, the scenario, and the output of
 * every stage before it. Passing that output forward is what makes this orchestration
 * and not a series of unrelated calls.
 */
public final class AgentContext {

    private final String runId;
    private final String requirement;
    private final String scenario;
    private final Stage stage;
    private final int attempt;
    private final String previousError;
    private final Map<Stage, String> upstreamOutputs;
    private final String reviewerFeedback;

    public AgentContext(String runId, String requirement, String scenario, Stage stage,
                        int attempt, String previousError, Map<Stage, String> upstreamOutputs) {
        this(runId, requirement, scenario, stage, attempt, previousError, upstreamOutputs, null);
    }

    public AgentContext(String runId, String requirement, String scenario, Stage stage,
                        int attempt, String previousError, Map<Stage, String> upstreamOutputs,
                        String reviewerFeedback) {
        this.runId = runId;
        this.requirement = requirement;
        this.scenario = scenario;
        this.stage = stage;
        this.attempt = attempt;
        this.previousError = previousError;
        this.upstreamOutputs = Collections.unmodifiableMap(new LinkedHashMap<>(upstreamOutputs));
        this.reviewerFeedback = reviewerFeedback;
    }

    public String getRunId() {
        return runId;
    }

    public String getRequirement() {
        return requirement;
    }

    public String getScenario() {
        return scenario;
    }

    public Stage getStage() {
        return stage;
    }

    /** 0 on the first try. A retrying agent should use this and {@link #getPreviousError()}. */
    public int getAttempt() {
        return attempt;
    }

    public String getPreviousError() {
        return previousError;
    }

    public Map<Stage, String> getUpstreamOutputs() {
        return upstreamOutputs;
    }

    public String getOutputFrom(Stage stage) {
        return upstreamOutputs.get(stage);
    }

    /**
     * What a human asked to change when they sent the run back for re-planning, or null
     * on a first pass. An agent re-running after a revision must take this into account.
     */
    public String getReviewerFeedback() {
        return reviewerFeedback;
    }
}
