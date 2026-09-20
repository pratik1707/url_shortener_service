package com.schwab.shortener.orchestrator;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a stage to the agent that serves it, and to the agent to fall back on.
 *
 * <p>Any {@link Agent} bean registers itself here by the stage it declares. A stage
 * with no registered agent uses a stub, so adding a live model for one stage does not
 * require providing one for all of them. Every stage also gets a {@link FallbackAgent},
 * which the engine turns to when the primary agent has used up its retries.
 */
@Component
public class AgentRegistry {

    private final Map<Stage, Agent> primary = new EnumMap<>(Stage.class);
    private final Map<Stage, Agent> fallback = new EnumMap<>(Stage.class);

    public AgentRegistry(List<Agent> agents) {
        for (Agent agent : agents) {
            primary.put(agent.stage(), agent);
        }
        for (Stage stage : Stage.values()) {
            primary.computeIfAbsent(stage, StubAgent::new);
            fallback.put(stage, new FallbackAgent(stage));
        }
    }

    public Agent forStage(Stage stage) {
        return primary.get(stage);
    }

    public Agent fallbackFor(Stage stage) {
        return fallback.get(stage);
    }

    public String listAgentNames() {
        StringBuilder sb = new StringBuilder();
        for (Stage stage : Stage.values()) {
            sb.append(stage).append('=').append(primary.get(stage).getName()).append(' ');
        }
        return sb.toString().trim();
    }
}
