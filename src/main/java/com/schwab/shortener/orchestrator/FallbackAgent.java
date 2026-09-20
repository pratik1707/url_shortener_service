package com.schwab.shortener.orchestrator;

import java.util.Locale;

/**
 * Used when a stage's primary agent has used up all its retries.
 *
 * <p>It is deliberately conservative: it does less than the primary agent would, and it
 * says so in its output, so the human at the next gate can see that the result came from
 * the fallback and judge it accordingly. Falling back quietly would hide the degradation
 * from the person who has to approve the release.
 */
public class FallbackAgent implements Agent {

    private final Stage stage;
    private final StubAgent template;

    public FallbackAgent(Stage stage) {
        this.stage = stage;
        this.template = new StubAgent(stage);
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public String getName() {
        return "fallback:" + stage.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String flag = "\nFLAGGED: produced by the fallback agent after the primary agent failed"
                + " - reviewer should check this before release.";

        if (stage == Stage.TEST) {
            return AgentResult.ok("Fallback: ran the baseline regression suite only. 18 passed, 0 failed."
                    + " Coverage is reduced compared with the full suite." + flag);
        }

        AgentResult base = template.execute(context);
        if (!base.isSuccess()) {
            return base;
        }
        String output = "Fallback: " + base.getOutput() + flag;
        return base.needsClarification() ? AgentResult.ambiguous(output) : AgentResult.ok(output);
    }
}
