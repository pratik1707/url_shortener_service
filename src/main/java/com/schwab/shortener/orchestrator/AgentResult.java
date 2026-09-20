package com.schwab.shortener.orchestrator;

/** What a stage produced, or why it could not. */
public final class AgentResult {

    private final boolean success;
    private final String output;
    private final String error;
    /** Set when the agent could not resolve an ambiguity on its own. */
    private final boolean needsClarification;

    private AgentResult(boolean success, String output, String error, boolean needsClarification) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.needsClarification = needsClarification;
    }

    public static AgentResult ok(String output) {
        return new AgentResult(true, output, null, false);
    }

    public static AgentResult ambiguous(String output) {
        return new AgentResult(true, output, null, true);
    }

    public static AgentResult failure(String error) {
        return new AgentResult(false, null, error, false);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }

    public boolean needsClarification() {
        return needsClarification;
    }
}
