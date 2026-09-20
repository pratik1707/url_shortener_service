---
name: orchestrator-governance
description: How to change the agentic SDLC orchestrator safely - stages, gates, agents, retries, fallback, re-planning, policy and audit. Use for any change under orchestrator/.
---
# Orchestrator governance rules

The orchestrator's value is that agents act inside fixed boundaries and humans own the
decisions. A change that makes a run faster by weakening a boundary is a regression.

## Never

- Let any stage run past an unapproved gate (`APPROVAL`, `RELEASE_APPROVAL`).
- Skip `PolicyGuard` (entry gate) or `StageExitCriteria` (exit gate) for any agent,
  including the fallback and the live model.
- Update or delete an `AuditEvent`. Corrections are new rows.
- Make retries or re-planning unbounded (`max-attempts`, `max-revisions`).
- Put a control in a prompt instead of in code. A model asked nicely is not a control.

## Adding a stage

1. Add it to `Stage` and its dependencies to `StageGraph`. The graph is validated acyclic
   at startup; add a `StageGraphTest` case for the new edges.
2. Decide whether it is a gate (`GATES`) and whether it changes something that must be
   undone on rollback (`COMPENSATABLE`).
3. Add exit criteria in `StageExitCriteria` if "done" has a checkable meaning.
4. Give `StubAgent` deterministic output for it, and `FallbackAgent` if the default is
   not right.
5. Update the console, README, ARCHITECTURE.md and the engine tests.

## Adding an agent

Implement `Agent` and register it as a bean for its stage. The engine does not change.
Its output goes through the same gates. A live-model agent must be off by default and
must never be required for `mvn verify`.

## Failure handling order

Attempt → exit gate → retry with the failure fed back (up to `max-attempts`) → one
fallback attempt, flagged → rollback of completed compensatable stages.

## Re-planning

Only while a run waits at a gate. Invalidate the changed stage and everything downstream
(`StageGraph.getDownstream`), compensate completed work first, pass the feedback to the
agents, and require the gates to be passed again.
