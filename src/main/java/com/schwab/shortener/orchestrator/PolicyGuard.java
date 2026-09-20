package com.schwab.shortener.orchestrator;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Rules checked before a stage is allowed to run.
 *
 * <p>The autonomy boundary has to be enforced somewhere other than the prompt. A model
 * asked nicely not to touch credentials is not a control; a check that refuses to start
 * the stage is. The list is short on purpose - what matters is where it sits.
 */
@Component
public class PolicyGuard {

    private static final List<String> FORBIDDEN_TERMS = List.of(
            "drop database", "drop table", "truncate", "delete from",
            "credential", "password", "secret key", "private key",
            "disable auth", "disable tls", "skip verification", "rm -rf");

    public static final class Decision {
        private final boolean allowed;
        private final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision block(String reason) {
            return new Decision(false, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }
    }

    /** Checked before the stage runs, against the requirement and the upstream plan. */
    public Decision check(Stage stage, String requirement, String plan) {
        String haystack = ((requirement == null ? "" : requirement) + " "
                + (plan == null ? "" : plan)).toLowerCase(Locale.ROOT);

        for (String term : FORBIDDEN_TERMS) {
            if (haystack.contains(term)) {
                return Decision.block("policy: requirement or plan references a restricted operation ('"
                        + term + "'); a human must author this change");
            }
        }

        // Change-control rule: nothing reaches RELEASE without a recorded human approval.
        // Enforced structurally by the graph, restated here so the rule is discoverable
        // in one place rather than implied by the edge list.
        return Decision.allow();
    }
}
