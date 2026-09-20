# The Three Scenarios

Each can be run from the console at <http://localhost:8080>. Pick the scenario, edit
the requirement, press Run.

---

## 1. Greenfield - a new capability

**Requirement:** `add a health endpoint that reports the active code strategy`

**What to watch.** REQUIREMENTS normalizes the request and reports no blocking
ambiguity. DESIGN names the surface it would touch. The run then **stops** at APPROVAL
and waits - the stages below it stay `PENDING`, and no thread is held. After Approve,
IMPLEMENT and DOCS start **together**, and the run **stops again** at RELEASE_APPROVAL
once both branches are done, showing the test and docs results. Approve again to release.

**Try re-planning.** At the first gate, type feedback such as `also report the counter
block size` and press **Request changes**. DESIGN re-runs with that feedback, the
revision is recorded as `REPLAN` in the audit trail, and the run stops at the gate again
with the new plan.

**Try a plan that shrinks.** Use `update the README to explain the alias rules`. DESIGN
decides no code changes are needed, so IMPLEMENT and TEST are marked `NOT_REQUIRED`,
`PLAN_ADJUSTED` appears in the audit trail, and the run goes straight from the docs to the
release gate.

**Decomposition:** requirement → impacted surface → implementation and documentation in
parallel → test → release readiness.

**Validation:** TEST's exit gate only passes on a report showing 0 failed, and a human
approves the release with that report in front of them, so nothing ships untested.

---

## 2. Brownfield - changing code that exists

**Requirement:** `migrate short code generation from the hash strategy to the counter strategy`

This is a real change in this repository - both strategies exist in the code, and
`shortener.strategy` switches between them - which is what makes it a fair demonstration
rather than a mock. The IMPLEMENT and TEST stages report on the change rather than
editing code; see ADR-002.

**What to watch.** DESIGN identifies the impacted modules - the `ShortCodeGenerator`
interface, `CounterShortCodeGenerator`, `UrlService.generateUniqueCode`, the stored
`strategy` column - and states a rollback: revert the binding, existing codes stay
resolvable. It also states that the HTTP contract does not change, which is what makes
the change safe to approve.

Then **TEST fails on its first attempt**, reporting that the binding still resolved
`hash-sha256`. The engine retries with the failure text fed back in, the second attempt
passes, and `RETRY` appears in the audit trail. That path is deterministic in the stub
so it can be demonstrated on demand rather than only when something genuinely breaks.

**Try the fallback.** Use the requirement `fix the flaky redirect test`. TEST fails all
three attempts, `FALLBACK` appears in the audit trail, and the fallback agent finishes the
stage with a reduced regression run. Its output is marked `FLAGGED`, and the release gate
shows `TEST (COMPLETED, fallback)`, so the approver decides with that in view.

**Codebase reasoning:** the change is contained because the two strategies already sit
behind one interface. The write path asks the generator whether collisions are possible
and skips the database lookup when they are not, so swapping the strategy also removes a
round-trip from the write path - a design consequence worth surfacing at review, not
after.

---

## 3. Ambiguous - an under-specified requirement

**Requirement:** `links should expire`

Expire after what? Measured from creation or last use? What is the default? What happens
to a link that has lapsed - gone, or a message?

**What to watch.** REQUIREMENTS returns with `AMBIGUITY` recorded: the assumptions it
made, flagged for the approver rather than buried. The gate then puts those assumptions
in front of a human **before** any code is written. That is the point - the orchestrator
does not resolve ambiguity by guessing quietly, it resolves it by making the guess
visible at the moment someone can correct it.

Two ways to answer it at the gate:

- **Clarify.** Choose *re-plan from REQUIREMENTS*, write the missing decision - e.g.
  `expire 30 days after creation by default` - and press **Request changes**.
  REQUIREMENTS re-runs with the answer, the ambiguity is gone, DESIGN is rebuilt on the
  clarified requirement, and the run stops at the gate again for approval.
- **Reject.** The run ends at the gate with the reason recorded. Nothing downstream ran.

### The ambiguity in the assignment itself

The specification asks for an orchestration layer with a dozen governance properties and
a URL shortener with core APIs, analytics and reliability features, in two to three days.
It does not say how much orchestrator is enough.

That was resolved the same way: explicitly, in writing, with what was excluded named
rather than implied. See [ADR-002](adr/002-orchestrator-scope.md).

---

## Engineering summary

**Plan.** Build the shortener as the substrate, with both code strategies behind one
interface so the migration between them is a contained change. Build the orchestrator's
governance for real and keep the agent work behind an interface. Use the migration as the
brownfield demonstration so one piece of work serves two requirements.

**Artifacts.** Runnable service and console; two code-generation strategies; orchestration
engine with graph, two approval gates, entry and exit gates, retries, fallback,
re-planning, rollback, safe stop, policy checks, audit and metrics; an optional live-model
agent; nine test classes; two ADRs.

**Risks and trade-offs.** In-process counter is correct in one JVM only - Redis behind the
same interface for production. Stub agents are the default so the governance is
provable; the live model adds real analysis for the text stages but not real code changes. Rollback is logical rather than
VCS-backed. H2 over Postgres for one-command startup.

**Assumptions.** Reads dominate writes. Codes are shared publicly but must not be
enumerable. Six characters is enough (56.8 billion). A human is available at the gate.

**Limitations.** Re-planning cannot add new stage types, IMPLEMENT and TEST do not touch
real code, analytics are a hit counter, single-node execution. Each is named in ADR-002
with what it would take to close.
