package com.schwab.shortener.orchestrator;

import java.util.Locale;

/**
 * Deterministic agent used when no language model is configured. Lets the orchestrator
 * run and be tested without network access, an API key, or cost.
 *
 * <p>A few words in the requirement switch on behaviour worth demonstrating:
 * <ul>
 *   <li>"migrate" - TEST fails its first attempt, then passes (retry).</li>
 *   <li>"flaky" - TEST fails every attempt, so the fallback agent takes over.</li>
 *   <li>"readme" or "documentation" - DESIGN scopes the change as docs-only, and the
 *       engine re-plans to drop IMPLEMENT and TEST.</li>
 * </ul>
 */
public class StubAgent implements Agent {

    /** Marker DESIGN writes when no code needs to change. The engine re-plans on it. */
    public static final String DOCS_ONLY_MARKER = "Scope: docs-only";

    private final Stage stage;

    public StubAgent(Stage stage) {
        this.stage = stage;
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public String getName() {
        return "stub:" + stage.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String requirement = context.getRequirement() == null ? "" : context.getRequirement();
        String lower = requirement.toLowerCase(Locale.ROOT);
        String feedback = context.getReviewerFeedback();

        switch (stage) {
            case REQUIREMENTS: {
                boolean ambiguous = "ambiguous".equalsIgnoreCase(context.getScenario()) && feedback == null;
                StringBuilder text = new StringBuilder("Normalized requirement: ").append(requirement)
                        .append("\nAcceptance: behaviour is observable through the public API and covered by tests.");
                if (feedback != null) {
                    text.append("\nClarified by reviewer: ").append(feedback)
                        .append("\nNo blocking ambiguity remains.");
                } else if (ambiguous) {
                    text.append("\nAMBIGUITY: the requirement does not state units, bounds or defaults.")
                        .append(" Assumed seconds, no upper bound, default absent. Flagged for the approver.");
                } else {
                    text.append("\nNo blocking ambiguity identified.");
                }
                return ambiguous ? AgentResult.ambiguous(text.toString()) : AgentResult.ok(text.toString());
            }
            case DESIGN: {
                boolean docsOnly = lower.contains("readme") || lower.contains("documentation");
                String impacted;
                if (docsOnly) {
                    impacted = "README.md and docs/ only";
                } else if ("brownfield".equalsIgnoreCase(context.getScenario())) {
                    impacted = "ShortCodeGenerator (interface), CounterShortCodeGenerator, UrlService.generateUniqueCode, ShortUrl.strategy";
                } else {
                    impacted = "new component plus its wiring in UrlService";
                }
                StringBuilder text = new StringBuilder("Impacted surface: ").append(impacted)
                        .append('\n').append(docsOnly
                                ? DOCS_ONLY_MARKER + " - no code changes, so IMPLEMENT and TEST are not required."
                                : "Scope: code change.")
                        .append("\nContract change: none at the HTTP layer.")
                        .append("\nRollback: ").append("brownfield".equalsIgnoreCase(context.getScenario())
                                ? "revert the generator binding; stored codes remain resolvable."
                                : "revert the change; nothing stored depends on it.");
                if (feedback != null) {
                    text.append("\nRevision: incorporated reviewer feedback - ").append(feedback);
                }
                text.append("\nUpstream: ").append(shorten(context.getOutputFrom(Stage.REQUIREMENTS)));
                return AgentResult.ok(text.toString());
            }
            case IMPLEMENT:
                return AgentResult.ok("Applied change described by DESIGN. Files touched: 3. "
                        + "No public contract modified.");
            case DOCS:
                return AgentResult.ok("Updated README and added an ADR entry for this change.");
            case TEST: {
                if (lower.contains("flaky")) {
                    return AgentResult.failure("test run did not complete: suite timed out on attempt "
                            + (context.getAttempt() + 1));
                }
                boolean shouldFailFirst = lower.contains("migrate") && context.getAttempt() == 0;
                if (shouldFailFirst) {
                    return AgentResult.failure("2 tests failed: expected strategy 'counter-base62-permuted' "
                            + "but binding still resolved 'hash-sha256'");
                }
                return AgentResult.ok("All tests green. 24 passed, 0 failed.");
            }
            case RELEASE:
                return AgentResult.ok("Release readiness: tests green, docs updated, both approvals recorded.");
            case APPROVAL:
            case RELEASE_APPROVAL:
            default:
                return AgentResult.ok("no-op");
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "(none)";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }
}
