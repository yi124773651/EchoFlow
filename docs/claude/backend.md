# Backend Conventions

If this file conflicts with `CLAUDE.md`, `CLAUDE.md` wins.

## 1. Layer Rules
- `web` may depend only on `application`.
- `application` may depend on `domain`.
- `domain` may depend only on JDK and domain-local abstractions.
- `infrastructure` may depend on `domain` and frameworks.

## 2. Responsibilities
- Web: request validation, auth handoff, DTO mapping, HTTP response only.
- Application: use-case orchestration, transactions, ports, policies.
- Domain: aggregates, entities, value objects, domain services, domain events, repository ports, invariants.
- Infrastructure: JPA/JDBC, AI clients, HTTP clients, tools, storage, messaging.

## 3. Forbidden
- No business orchestration in controllers.
- No repositories called directly from controllers.
- No Spring/JPA/HTTP/AI SDK code in Domain.
- No persistence entities exposed to Web.
- No long transaction around AI calls, remote calls, or SSE.

## 4. Modeling Rules
- Aggregate boundaries define consistency boundaries.
- Repositories are defined per aggregate root.
- Entities protect invariants through methods, not public setters.
- Value objects should be immutable; use `record` when natural.
- Domain events represent business facts, not technical noise.

## 5. Persistence Rules
- Repository interfaces live in Domain.
- Repository implementations live in Infrastructure.
- JPA entities are persistence models, not domain models.
- Use explicit mapping between persistence and domain.
- Use JPA for aggregate writes by default.
- Use JdbcTemplate or projections for read-heavy queries when simpler.

## 6. Transaction Rules
- Transactions belong in Application services.
- Keep transactions short.
- Persist/commit before external calls when possible.
- Use optimistic locking when concurrent aggregate updates matter.

## 7. Error Rules
- Domain throws business exceptions only.
- Domain exceptions must not contain HTTP status or web payloads.
- Use `@RestControllerAdvice` for centralized exception mapping.
- Return RFC 7807 `ProblemDetail`.
- Do not leak stack traces to clients.

## 8. Java 21 Rules
- `record` for DTOs and immutable carriers.
- `sealed interface` / `sealed class` for closed hierarchies.
- `switch` + pattern matching when it improves clarity.
- Prefer `Instant` for timestamps.
- Avoid Lombok in Domain.
- Use `Optional` mainly for return values.

## 9. Virtual Threads
- Use virtual threads for blocking I/O concurrency.
- Avoid `synchronized` in long-running, blocking, or I/O-heavy paths.
- Use timeouts for all external calls.
- Be careful with `ThreadLocal`.
- Avoid unbounded executors and task queues.

## 10. Testing
- Domain: pure unit tests, no Spring.
- Application: mock ports, verify orchestration and policies.
- Infrastructure: integration tests, use Testcontainers for PostgreSQL.
- Web: test validation, serialization, and error mapping.

## 11. Done
A backend change is not done unless:
- boundaries stay clean
- tests cover behavior
- schema changes include Flyway migration
- API changes trigger `/sync-api`