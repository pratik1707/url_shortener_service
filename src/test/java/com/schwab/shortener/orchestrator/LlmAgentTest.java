package com.schwab.shortener.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline checks of the live-model agent: what it sends and how it reads the reply. No network. */
class LlmAgentTest {

    private final LlmAgent agent = new LlmAgent(Stage.DESIGN, "test-key", "test-model", 500, Duration.ofSeconds(5));

    @Test
    @DisplayName("the prompt carries upstream output, reviewer feedback and the last failure")
    void promptCarriesContext() throws Exception {
        AgentContext context = new AgentContext("run_1", "add a health endpoint", "greenfield", Stage.DESIGN,
                1, "exit gate: design must state a rollback plan",
                Map.of(Stage.REQUIREMENTS, "Acceptance: GET /health returns 200"),
                "report the active strategy");

        String body = agent.buildRequestBody(context);

        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("Acceptance: GET /health returns 200"));
        assertTrue(body.contains("report the active strategy"));
        assertTrue(body.contains("must state a rollback plan"));
        assertTrue(body.contains("Rollback:"), "the system prompt should ask for the rollback line");
    }

    @Test
    @DisplayName("text blocks in the reply are joined; other blocks are ignored")
    void replyTextIsExtracted() throws Exception {
        String reply = "{\"content\":[{\"type\":\"text\",\"text\":\"Impacted: UrlService. \"},"
                + "{\"type\":\"tool_use\",\"id\":\"x\"},"
                + "{\"type\":\"text\",\"text\":\"Rollback: revert.\"}]}";

        assertEquals("Impacted: UrlService. Rollback: revert.", agent.extractText(reply));
    }
}
