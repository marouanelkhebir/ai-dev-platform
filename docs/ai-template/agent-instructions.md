# Agent instructions — <service name>

Copy this file to `.ai/agent-instructions.md`. It is the first document every agent reads, and the
one with the highest priority among the `.ai/*` files.

Keep it short. This is not documentation, it is a set of standing orders.

## Before you start

- Read `.ai/architecture.md` and `.ai/domain.md`.
- Look at `<a reference class>` for the style this repository expects.

## Standing orders

- `<e.g. never modify the database schema without a Liquibase changeset in src/main/resources/db>`
- `<e.g. every new endpoint needs an entry in the OpenAPI spec under src/main/resources/openapi>`
- `<e.g. the module payment-core is frozen; propose changes there instead of making them>`

## Files you must not touch

```
src/main/resources/db/migration/   already applied migrations
src/main/resources/openapi/legacy/ frozen contracts
```

## How to run things here

See `.ai/commands.md`.

## Who to ask

When the ticket is ambiguous, say so and stop. The team that owns this service is `<team>`, and a
wrong assumption here costs more than a round trip.
