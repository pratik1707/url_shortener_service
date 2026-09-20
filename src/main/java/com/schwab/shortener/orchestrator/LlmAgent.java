package com.schwab.shortener.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * A stage agent backed by a live language model (the Anthropic Messages API).
 *
 * <p>Off by default. Switched on with {@code orchestrator.llm.enabled=true} and an API
 * key, and then only for the stages where free text is the output: REQUIREMENTS, DESIGN
 * and DOCS. The engine treats it exactly like the stub - same interface, same entry and
 * exit gates, same retries - and if the model keeps failing, the fallback agent takes
 * over. None of the governance depends on the model behaving.
 *
 * <p>Uses the JDK HTTP client rather than an SDK so the project gains no dependency.
 */
public class LlmAgent implements Agent {

    static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String API_VERSION = "2023-06-01";

    private final Stage stage;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmAgent(Stage stage, String apiKey, String model, int maxTokens, Duration timeout) {
        this.stage = stage;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public String getName() {
        return "llm:" + stage.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public AgentResult execute(AgentContext context) {
        try {
            String body = buildRequestBody(context);
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(timeout)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return AgentResult.failure("model call failed: HTTP " + response.statusCode());
            }

            String text = extractText(response.body());
            if (text.isBlank()) {
                return AgentResult.failure("model returned no text");
            }
            if (stage == Stage.REQUIREMENTS && text.contains("AMBIGUITY:")) {
                return AgentResult.ambiguous(text);
            }
            return AgentResult.ok(text);
        } catch (IOException e) {
            return AgentResult.failure("model call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResult.failure("model call interrupted");
        }
    }

    /** The JSON body sent to the model. Package-private so it can be tested offline. */
    String buildRequestBody(AgentContext context) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("system", systemPrompt(stage));

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(context));
        return mapper.writeValueAsString(root);
    }

    /** Joins the text blocks of a Messages API response. */
    String extractText(String responseJson) throws IOException {
        JsonNode content = mapper.readTree(responseJson).path("content");
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }
        return sb.toString().trim();
    }

    static String systemPrompt(Stage stage) {
        String common = "You are one stage in an SDLC orchestrator for a Java 21 / Spring Boot URL shortener"
                + " (packages com.schwab.shortener.url and com.schwab.shortener.orchestrator). Reply in plain"
                + " text, under 250 words, no markdown headings. A human reviews your output at an approval gate.";
        switch (stage) {
            case REQUIREMENTS:
                return common + " Stage: REQUIREMENTS. Restate the requirement as a clear engineering problem."
                        + " Include a line starting 'Acceptance:' with testable acceptance criteria."
                        + " If anything needed to build it is unstated (units, limits, defaults, behaviour on"
                        + " failure), include a line starting 'AMBIGUITY:' listing the gaps and the assumptions"
                        + " you made. If reviewer feedback is given, treat it as the answer to those gaps.";
            case DESIGN:
                return common + " Stage: DESIGN. Name the impacted modules, APIs and data, the sequence of"
                        + " tasks, and any contract change. Include a line starting 'Rollback:' describing how"
                        + " to undo the change. If no code needs to change, include the exact line '"
                        + StubAgent.DOCS_ONLY_MARKER + "'. If reviewer feedback is given, revise the design"
                        + " to address it and say how.";
            case DOCS:
                return common + " Stage: DOCS. Write the README section and a short ADR entry for this change.";
            default:
                return common + " Stage: " + stage + ".";
        }
    }

    static String userPrompt(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scenario: ").append(context.getScenario()).append('\n');
        sb.append("Requirement: ").append(context.getRequirement()).append('\n');
        for (Map.Entry<Stage, String> upstream : context.getUpstreamOutputs().entrySet()) {
            sb.append("\nOutput of ").append(upstream.getKey()).append(":\n").append(upstream.getValue()).append('\n');
        }
        if (context.getReviewerFeedback() != null) {
            sb.append("\nReviewer feedback to address: ").append(context.getReviewerFeedback()).append('\n');
        }
        if (context.getPreviousError() != null) {
            sb.append("\nYour previous attempt was rejected: ").append(context.getPreviousError())
              .append(". Fix that in this attempt.\n");
        }
        return sb.toString();
    }
}
