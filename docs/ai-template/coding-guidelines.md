# Coding guidelines — <service name>

Copy to `.ai/coding-guidelines.md`. Read by the developer and reviewer agents.

## Java

- Java 21. Records for value objects, sealed interfaces where the set of cases is closed.
- Constructor injection only. No `@Autowired` on fields.
- Immutable by default; `final` fields, defensive copies of collections.
- `Optional` as a return type, never as a field or a parameter.
- No `catch (Exception e)` without either rethrowing or acting on it.

## Spring

- `@Transactional` on application services, never on controllers or repositories.
- `readOnly = true` on read paths.
- Every outbound HTTP client declares connect and read timeouts.
- Configuration through `@ConfigurationProperties` records, never `@Value` scattered in services.

## Naming

- `<conventions specific to this repository>`

## Logging

- Structured, with `<the correlation key used here>`.
- Never log: tokens, credentials, customer identifiers, account numbers, amounts tied to a person.

## Things this team rejects in review

- `<e.g. utility classes with static state>`
- `<e.g. Lombok @Data on entities>`
