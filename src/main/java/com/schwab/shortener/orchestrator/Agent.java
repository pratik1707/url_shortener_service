package com.schwab.shortener.orchestrator;

/**
 * The unit of work behind a stage.
 *
 * <p>The engine does not know or care whether an implementation calls a language
 * model, runs a build, or returns a canned answer. That boundary is deliberate: it
 * keeps the governance logic testable without a network, and it means swapping a
 * stub for a live model changes no orchestration code.
 */
public interface Agent {

    /** Which stage this agent serves. */
    Stage stage();

    AgentResult execute(AgentContext context);

    /** Identifies the agent in the audit trail. */
    String getName();
}
