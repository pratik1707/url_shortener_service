package com.schwab.shortener.orchestrator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Registers live-model agents for REQUIREMENTS, DESIGN and DOCS - only when
 * {@code orchestrator.llm.enabled=true}. Without it, every stage uses the stub and the
 * app needs no key and no network.
 */
@Configuration
@ConditionalOnProperty(name = "orchestrator.llm.enabled", havingValue = "true")
public class LlmAgentConfig {

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;

    public LlmAgentConfig(@Value("${orchestrator.llm.api-key:}") String apiKey,
                          @Value("${orchestrator.llm.model:claude-sonnet-4-5}") String model,
                          @Value("${orchestrator.llm.max-tokens:800}") int maxTokens,
                          @Value("${orchestrator.llm.timeout-seconds:60}") int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("orchestrator.llm.enabled=true but no API key is set;"
                    + " set ANTHROPIC_API_KEY or turn the live model off");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Bean
    public Agent llmRequirementsAgent() {
        return new LlmAgent(Stage.REQUIREMENTS, apiKey, model, maxTokens, timeout);
    }

    @Bean
    public Agent llmDesignAgent() {
        return new LlmAgent(Stage.DESIGN, apiKey, model, maxTokens, timeout);
    }

    @Bean
    public Agent llmDocsAgent() {
        return new LlmAgent(Stage.DOCS, apiKey, model, maxTokens, timeout);
    }
}
