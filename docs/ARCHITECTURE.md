# Architecture

## Components

```
                      ┌──────────────────────────────┐
   browser ──────────►│  console (static/index.html) │
                      └───────────┬──────────────────┘
                                  │
            ┌─────────────────────┴─────────────────────┐
            ▼                                           ▼
   ┌──────────────────┐                      ┌────────────────────────┐
   │  UrlController   │                      │ OrchestratorController │
   └────────┬─────────┘                      └───────────┬────────────┘
            ▼                                            ▼
   ┌──────────────────┐                      ┌────────────────────────┐
   │   UrlService     │                      │  OrchestrationEngine   │
   └────────┬─────────┘                      └───────────┬────────────┘
            ▼                                  ┌─────────┼──────────┐
   ┌──────────────────┐                        ▼         ▼          ▼
   │ShortCodeGenerator│                  StageGraph  AgentRegistry  PolicyGuard + StageExitCriteria
   │  hash | counter  │                                  │
   └────────┬─────────┘                                  ▼
            ▼                                        ┌───────┐
   CounterBlockAllocator                             │ Agent │  stub | model
   (in-memory | redis)                               └───────┘  primary + fallback
            │                                            │
            └──────────────► H2 / Postgres ◄──────────────┘
                 short_url · orchestration_run · stage_execution · audit_event
```

## Control flow of a run

1. `POST /orchestrator/runs` creates a `Run` and one `PENDING` `StageExecution` per
   stage, writes `RUN_STARTED`, and hands the run to a worker thread.
2. The engine loops: find every stage whose dependencies are `COMPLETED` (or
   `NOT_REQUIRED`), run them together, save, re-plan if an output calls for it, repeat.
3. A gate stops everything. The stage becomes `AWAITING_APPROVAL`, the run becomes
   `AWAITING_APPROVAL`, the thread is released. Even if other stages are ready, parking
   here keeps the guarantee simple - nothing downstream of an unapproved decision runs,
   and there is one place a human has to look.
4. Each stage is policy-checked (entry gate), then attempted up to `max-attempts`, with
   the previous failure passed into the next attempt. A result only counts once it passes
   `StageExitCriteria` (exit gate); otherwise the violation becomes the failure text.
5. Retries exhausted → one attempt by the `FallbackAgent`, whose output is flagged.
6. Fallback also fails → compensation. Completed stages that changed something are
   reversed in order, remaining stages are skipped, the run is `ROLLED_BACK`.
7. Approval resumes the loop from where it stopped. There are two gates: the plan
   (`APPROVAL`) and the release (`RELEASE_APPROVAL`).
8. At a gate, a human can instead send the run back with feedback. Everything downstream
   of the chosen stage is invalidated - completed changes compensated first - and re-run
   with the feedback in the agent's context. Bounded by `max-revisions`.

## Why state is in the database

A run parked at a gate holds no thread and survives a restart. Resuming is another call
to `advance(runId)`. That durability is what makes the gate a control rather than a
pause in a thread that dies with the process - and it is also what makes the audit trail
and the metrics recoverable rather than best-effort.

## Data model

| Table | Holds |
|---|---|
| `short_url` | code (PK), target, created, expires, creator, strategy |
| `orchestration_run` | requirement, scenario, status, timings, failure reason, revision count, latest feedback |
| `stage_execution` | one row per stage per run: status, attempts, output, duration, which agent produced it, whether the fallback did |
| `audit_event` | append-only record of every step and every human decision |

`stage_execution.output` is the decision lineage: each stage records what it produced,
and `AgentContext` passes those outputs to every stage downstream.

## Extension points

| To change | Implement | Touches |
|---|---|---|
| Code strategy | `ShortCodeGenerator` | one bean in `AppConfig` |
| Counter across instances | `CounterBlockAllocator` | one bean |
| Real AI for a stage | `Agent` (see `LlmAgent`) | nothing in the engine |
| What "done" means for a stage | `StageExitCriteria` | one class |
| Policy rules | `PolicyGuard` | one class |
| Stage graph | `StageGraph` | validated acyclic at startup |
