---
name: spring-testing
description: How to write and fix tests in this project. Use for "write a test", "add integration test" or "fix broken test".
---
# Testing guidelines

1. **Pick the smallest test that proves the behaviour.** Plain unit tests for pure logic
   (`Base62Test`, `StageGraphTest`, `StageExitCriteriaTest`). `@SpringBootTest` with the
   `test` profile when you need the database or real HTTP.
2. **H2, not Testcontainers - deliberately.** The project promises `mvn verify` with no
   Docker. The test profile uses an in-memory H2 database. Do not add Testcontainers
   without asking; if Postgres-specific behaviour ever matters, that is the time.
3. **Cover both sides.** Every change tests the success path (200, 201, 302) and the
   failure boundaries (400, 404, 409, 410).
4. **Deterministic only.** No real network, no sleeps to "wait long enough", no live
   model. Orchestrator tests use the stub agents and poll for the expected state with a
   deadline (`waitUntil`). Time-dependent tests control the `Clock`.
5. **Name tests after behaviour**, with a `@DisplayName` sentence a reviewer can read.
6. **Protect the reasons for key decisions.** The no-collision tests for short codes and
   the gate and rollback tests for the orchestrator are the ones that must never be
   weakened to make a change pass.
