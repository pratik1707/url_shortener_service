# CLAUDE.md

Guidance for Claude (or any AI assistant) working in this repository. The skills in
`.claude/skills/` go deeper on specific kinds of change.

## Build and test

- Build: `mvn clean compile`
- All tests: `mvn verify`
- One test class: `mvn test -Dtest=OrchestrationEngineTest`
- Run locally: `mvn spring-boot:run`, then open http://localhost:8080
- Live model (optional): `ANTHROPIC_API_KEY=... ORCHESTRATOR_LLM_ENABLED=true mvn spring-boot:run`

The app must keep running with one command and no Docker, database or API key. Do not
add anything that breaks that without asking first.

## Code style

- Java 21. Use `record` for DTOs and API payloads; pattern matching and switch
  expressions where they make code clearer, not by default.
- Constructor injection only. No `@Autowired` on fields in production code.
- Validate inputs with `jakarta.validation.constraints` on the request record.
- Never return a JPA entity from a controller. Map it to a record first
  (see `UrlStatsResponse.from`).
- Reads are `@Transactional(readOnly = true)`, writes are `@Transactional`.
- Plain, readable method and test names. Test names describe behaviour
  (`rejectingStopsTheRun`), with a `@DisplayName` sentence.
- Comments are short and explain why, not what. Readability over cleverness.
- Use plain hyphens, not long dashes, in code, comments and docs.

## Rules that are easy to break by accident

- Redirects are `302`, never `301`. A cached 301 would stop expiry and click counting.
- Generated codes are exactly 6 characters; aliases are 7 to 16. Do not let the two
  ranges overlap - the counter path skips the "is this code free" lookup because of it.
- Expiry is checked on read and answered with `410 Gone`, not `404`.
- The audit trail is append-only. Never update or delete an `AuditEvent`.
- Nothing may skip an approval gate, the policy check, or the exit criteria.

## Decisions

Significant design decisions get an ADR in `docs/adr/`. If a change contradicts an ADR,
update the ADR in the same change. Keep `README.md` and `docs/` in step with the code.
