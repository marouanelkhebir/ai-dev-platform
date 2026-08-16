# Architecture — <service name>

Copy this file to `.ai/architecture.md` in your repository and fill it in. The architect and reviewer
agents read it before touching anything.

Write what an experienced developer joining the team would need to know, and nothing else. The
agents already read the code; what they cannot infer is the *intent*.

## Layers

Describe the layering and what each layer may depend on. Example:

```
api/          REST controllers, DTOs. No business logic, no entity leaks out of this layer.
application/  Use cases, transaction boundaries.
domain/       Entities, value objects, domain services. No Spring, no JPA annotations.
infrastructure/ JPA repositories, HTTP clients, Kafka producers and consumers.
```

## Rules that must not be broken

- Transaction boundaries live in `application/`, never in a controller and never in a repository.
- No entity is ever returned by a controller; map to a DTO.
- Outbound HTTP goes through `infrastructure/client/`, never through a `RestTemplate` created inline.
- Every Kafka consumer is idempotent; the deduplication key is `<...>`.

## Cross-cutting concerns

- Error handling: `<how errors become HTTP responses>`
- Logging: `<what must be logged, and what must never be>`
- Configuration: `<where properties live, which are per-environment>`
- Feature flags: `<mechanism>`

## What this service must never do

List the things that look reasonable but are wrong here — this is the section that saves the most
review cycles.

- `<e.g. never call the pricing service synchronously in the payment path>`
