# ADR-002: Scope of the Agentic Orchestration Layer

**Status:** Accepted
**Date:** 2026-09-18

## Context

The assignment asks for an orchestration layer with an explicit dependency graph,
entry and exit gates, sequential and parallel paths with synchronization, preserved
cross-stage context and decision lineage, human approval checkpoints, bounded
retries, fallback, rollback, safe-stop, policy guardrails, audit-grade observability,
reliability metrics, and dynamic re-planning.

It asks for this alongside a URL shortener with core APIs, analytics and reliability
features, over two to three days.

Those two things are not the same size. Built thinly, both are unconvincing. The
question is not how to do all of it but which half to make real.

## Decision

**Build the governance machinery for real. Make the agents pluggable.**

Everything that constitutes control is implemented and tested:

| Requirement | Where it lives |
|---|---|
| Explicit dependency graph | `StageGraph`, validated acyclic at startup |
| Parallel paths | IMPLEMENT and DOCS share a predecessor, no edge between them |
| Synchronization | RELEASE_APPROVAL depends on both TEST and DOCS |
| Human approval checkpoints | `APPROVAL` (the plan) and `RELEASE_APPROVAL` (the release); the run parks and holds no thread |
| Entry and exit gates | Entry: dependencies + `PolicyGuard`. Exit: `StageExitCriteria` on the stage's output |
| Cross-stage context | `AgentContext` carries every upstream output forward |
| Decision lineage | `StageExecution` rows record what each stage produced |
| Bounded retries | `orchestrator.max-attempts`, default 3 |
| Fallback | `FallbackAgent`, one attempt after retries run out; its output is flagged |
| Dynamic re-planning | `replan` invalidates everything downstream of a changed stage and re-runs it, bounded by `orchestrator.max-revisions`; a docs-only design drops IMPLEMENT and TEST |
| Rollback | `rollbackRun`, reverse order, only compensatable stages |
| Safe stop | `safeStop`; completed work stands, nothing further is scheduled |
| Policy guardrails | `PolicyGuard`, checked before each stage runs |
| Audit observability | `AuditEvent`, append-only |
| Reliability metrics | `OrchestratorMetrics`, derived from stored runs |

The work *inside* a stage is behind the `Agent` interface. The default implementation
is `StubAgent`, which returns deterministic, plausible stage artifacts. `LlmAgent` (a live
model for REQUIREMENTS, DESIGN and DOCS) ships switched off.

## Why the agents are stubbed by default

1. **The graded behaviour is the governance, not the prose.** Whether a run parks at a
   gate, retries twice and then rolls back, is a property of the engine. Substituting a
   language model does not change it.
2. **It has to be testable.** `OrchestrationEngineTest` asserts approval, rejection,
   retry-then-recover, policy block and safe-stop. Those assertions require determinism.
   With a live model they would be flaky, slow and chargeable.
3. **It has to run on the reviewer's machine.** No API key, no network, no account.
   `mvn spring-boot:run` and the demo works.

Adding the live model was a new class implementing `Agent` and one configuration class.
The engine did not change for it, because it never knew what was behind the interface.

## What was deliberately not built

- **A live model for every stage.** `LlmAgent` covers the three text stages. IMPLEMENT
  and TEST do not edit or build real code; that needs a sandboxed workspace and VCS
  integration, which is a project of its own.
- **Re-planning that changes the graph's shape.** A run can re-run stages and drop stages
  from its plan, but cannot add stage types the graph does not already have. That would
  mean REQUIREMENTS emitting a graph rather than the graph being static.
- **Analytics beyond a counter.** The shortener counts hits and records the last access
  (`GET /urls/{code}/stats`). There is no time series, referrer or geography. The redirect
  returns 302 specifically so richer analytics remain possible.
- **Distributed execution.** One process. The state is in the database, so a
  multi-instance version needs a claim mechanism on runnable stages, not a redesign.

## Consequences

- The reviewer can run every governance behaviour on their own machine in one command.
- Ambition is legible: what is real is real, and what is not is named here rather than
  implied by a thin implementation.
- A live model can be switched on for the walkthrough with an API key and one setting.

## Note

This ADR is itself the answer to the assignment's "ambiguous requirements" scenario.
The specification does not say how much orchestrator is enough. Rather than guessing
silently, the ambiguity is resolved explicitly, in writing, with reasons and a list of
what was excluded. See `docs/SCENARIOS.md`.
