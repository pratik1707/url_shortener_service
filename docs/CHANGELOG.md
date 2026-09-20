# Changelog

## 1.1 - orchestrator governance

Closes the gaps between the first version and the assignment's orchestration
requirements. Design reasoning is in [ADR-002](adr/002-orchestrator-scope.md).

### Added

- **Release approval gate.** New `RELEASE_APPROVAL` stage between the join of TEST and
  DOCS and `RELEASE`. The approver sees the implementation, test and docs results,
  including whether a fallback produced any of them.
- **Exit gates.** `StageExitCriteria` checks each stage's output before it counts as done:
  requirements need acceptance criteria, a design needs a rollback plan, a test report
  must show 0 failed. A failed check is treated as a failed attempt and retried.
- **Fallback agent.** When a stage's retries run out, `FallbackAgent` makes one attempt.
  Its output is marked `FLAGGED` and the stage is recorded as `usedFallback`.
- **Re-planning.** `POST /orchestrator/runs/{id}/revise` sends a run waiting at a gate
  back to an earlier stage with feedback. Everything downstream is compensated if needed,
  reset and re-run with the feedback in the agent's context, and the gates must be passed
  again. Bounded by `orchestrator.max-revisions` (default 3).
- **Plan adjustment.** A design marked docs-only takes IMPLEMENT and TEST out of the run
  (`NOT_REQUIRED`), recorded as `PLAN_ADJUSTED`.
- **Live model agent.** `LlmAgent` calls the Anthropic Messages API for REQUIREMENTS,
  DESIGN and DOCS. Off by default; on with `ORCHESTRATOR_LLM_ENABLED=true` and
  `ANTHROPIC_API_KEY`.
- **Lineage fields.** Each stage records which agent produced its output and whether it
  was the fallback. Each run records its revision count and latest feedback.
- **Metrics.** `fallbackCount` and `replanCount`.
- **Console.** Release gate view, "Request changes" with a choice of stage to re-plan
  from, fallback marker on stages, new metrics.
- **AI assistant guidance.** `CLAUDE.md` and skills in `.claude/skills/`.
- **Tests.** `StageExitCriteriaTest`, `LlmAgentTest`, new `StageGraphTest` cases, and
  engine tests for the release gate, fallback, re-planning (from design, from
  requirements, after release was prepared, bounded) and the docs-only plan.

### Changed

- Request and response DTOs are Java records. Stats responses are built with
  `UrlStatsResponse.from`, so the entity never leaves the service.
- `GET /{code}` only matches letters and digits, so it no longer captures
  `/index.html` and the console loads at `/`.
- Rejections at a gate record which gate was rejected.

### Upgrading a local copy

The stage and status enums gained values, and the existing file database cannot store
them. Delete `./data` before starting the new version.
