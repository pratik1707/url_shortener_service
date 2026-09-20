package com.schwab.shortener.orchestrator;

import java.util.Locale;
import java.util.Optional;

/**
 * Exit gate: what a stage's output must contain before the stage counts as done.
 *
 * <p>An agent reporting success is not the same as the stage being finished. A design
 * with no rollback plan, or a test report that does not say nothing failed, is sent back
 * as a failed attempt - the violation is fed into the retry so the agent can correct it.
 * This matters most when the agent is a language model, whose output is not guaranteed
 * to follow instructions.
 */
public final class StageExitCriteria {

    private StageExitCriteria() {
    }

    /** @return the reason the output fails the exit gate, or empty if it passes */
    public static Optional<String> check(Stage stage, String output) {
        if (output == null || output.isBlank()) {
            return Optional.of("exit gate: " + stage + " produced no output");
        }
        String text = output.toLowerCase(Locale.ROOT);

        switch (stage) {
            case REQUIREMENTS:
                if (!text.contains("acceptance")) {
                    return Optional.of("exit gate: requirements must state acceptance criteria");
                }
                break;
            case DESIGN:
                if (!text.contains("rollback")) {
                    return Optional.of("exit gate: design must state a rollback plan");
                }
                break;
            case TEST:
                if (!text.contains("0 failed")) {
                    return Optional.of("exit gate: test report must show 0 failed");
                }
                break;
            default:
                break;
        }
        return Optional.empty();
    }
}
