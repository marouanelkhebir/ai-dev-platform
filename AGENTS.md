# Repository Guidelines

## Project Structure

This is a Java 21 / Spring Boot 3.3 Maven service. Production code is under `src/main/java/com/mel/aidev/`; packages map to platform concerns: `api/` for HTTP endpoints, `workflow/` for the persisted state machine, `agent/` and `tool/` for LLM agents, `persistence/` for JPA, `settings/` for persisted platform configuration and build profiles, and `security/` and `sandbox/` for enforcement. Configuration and Flyway migrations (`src/main/resources/db/migration/`) live in `src/main/resources/`; test configuration is `src/test/resources/application-test.yml`.

Tests mirror production packages in `src/test/java/`. Repository-onboarding templates belong in `docs/ai-template/`; container definitions are in `docker/`, `Dockerfile`, and `docker-compose.yml`.

## Build, Test, and Local Development

- `mvn clean compile` — compile the application.
- `mvn test` — run unit tests (`*Test.java`); integration tests are excluded.
- `mvn verify` — run unit and integration tests (`*IT.java`) and create JaCoCo output at `target/site/jacoco/`.
- `mvn spring-boot:run` — start the service with local configuration.
- `docker compose up --build` — start the platform and its local dependencies.

Use JDK 21 and Maven 3.9+. Copy `.env.example` to `.env` before running the Compose stack; never commit credentials or tokens.

The Maven test plugins set `net.bytebuddy.experimental=true`, so Mockito-based tests can still run when a developer uses a JDK newer than the Java 21 target. Do not remove that property without confirming the supported local JDK range.

## Coding Style and Naming

Use four-space indentation and conventional Java/Spring style. Keep packages focused and classes named by role, such as `WorkflowEngine`, `GitLabClient`, and `WorkflowResponse`. Use `PascalCase` for types, `camelCase` for methods and fields, and descriptive enum constants in `UPPER_SNAKE_CASE`. Prefer typed domain records and enforce workflow/security invariants in code rather than prompts. No formatter or linter is configured, so match nearby code and keep imports and methods tidy.

## Testing Guidelines

Write JUnit 5 tests beside the matching package. Name fast tests `*Test` and infrastructure, HTTP, or database tests `*IT`; Surefire excludes `*IT`, while Failsafe runs them during `verify`. Use WireMock for external Jira/GitLab contracts, H2 for fast contexts, and Testcontainers PostgreSQL when migration behavior matters. Add regression coverage for workflow transitions, command/path guards, secret handling, and Flyway changes.

## Commits and Merge Requests

Git history is not available in this checkout, so no repository-specific commit pattern can be verified. Use concise imperative subjects; when applicable, prefix with the Jira key, for example `BANK-1245 validate webhook signatures`. Keep each commit focused. Merge requests should explain the behavior change, link the Jira issue, list validation commands and results, call out config or migration changes, and include API examples or screenshots when user-visible behavior changes. Never merge generated secrets, `.env`, or `target/` output.
