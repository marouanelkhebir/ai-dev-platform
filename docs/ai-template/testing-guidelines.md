# Testing guidelines — <service name>

Copy to `.ai/testing-guidelines.md`. Read by the developer, test and acceptance agents.

## Layers

| Kind | Tooling | Where |
|---|---|---|
| Unit | JUnit 5 + Mockito, no Spring context | `src/test/java/**/*Test.java` |
| Integration | `@SpringBootTest`, Testcontainers, WireMock | `src/test/java/**/*IT.java` |
| Repository | `@DataJpaTest` + Testcontainers PostgreSQL | `src/test/java/**/repository` |
| Acceptance | Cucumber | `src/test/resources/features/*.feature` |
| Contract | `<Pact / Spring Cloud Contract>` | `<path>` |

## Rules

- One acceptance criterion, one test that fails if the criterion is violated.
- Test names state behaviour: `shouldSuspendActiveFeeWhenCustomerBecomesFragile`.
- No `Thread.sleep` — use Awaitility.
- No test that passes regardless of the production code.
- Never weaken an existing assertion to make a build green.

## Cucumber

- Scenarios are written in the business language of the ticket.
- One scenario per acceptance criterion, titled with the criterion.

## Commands

See `.ai/commands.md`.
