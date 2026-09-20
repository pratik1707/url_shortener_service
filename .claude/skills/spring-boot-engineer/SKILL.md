---
name: spring-boot-engineer
description: How to add or change Spring Boot controllers, services, entities and repositories in this project. Use for requests like "create endpoint", "add a field", "new controller" or "add db table".
---
# Spring Boot engineering guide

Work through the layers in this order.

1. **Entity.** Spring Data JPA with an explicitly typed `@Id`. Map enums with
   `@Enumerated(EnumType.STRING)`. New columns on existing tables must be nullable (or
   have a default) so existing data in `./data` still loads. If you add a relationship,
   map `@ManyToOne` with `FetchType.LAZY`.
2. **Repository.** Extend `JpaRepository<Entity, IdType>`. Prefer derived queries
   (`findByRunIdOrderByIdAsc`) over native SQL.
3. **DTO.** A `record`, with `jakarta.validation` constraints on request records. Never
   return an entity from a controller; add a static `from(entity)` factory on the
   response record instead.
4. **Service.** Business logic only. `@Transactional(readOnly = true)` for reads,
   `@Transactional` for writes. Take a `Clock` instead of calling `Instant.now()`, so tests
   can control time.
5. **Controller.** `@RestController` with explicit `@GetMapping` / `@PostMapping`. Return
   `ResponseEntity` with the status set explicitly. Map exceptions to status codes in an
   exception handler, not with try/catch in each method.

Always constructor injection. Keep the one-command, no-infrastructure startup working.
Update README.md's API table when you add or change an endpoint.
