# Domain — <service name>

Copy to `.ai/domain.md`. This is the file that prevents an agent from inventing business rules.

## Ubiquitous language

| Term | Meaning | Not to be confused with |
|---|---|---|
| `<Fragile customer>` | `<regulatory definition, with the reference>` | `<overdrawn customer>` |
| `<Active fee>` | `<definition>` | `<pending fee>` |

## Invariants

Rules that must hold at all times, whatever the code path:

- `<e.g. a suspended fee is never charged, even by the nightly batch>`
- `<e.g. an account always has at least one holder>`

## Aggregates and boundaries

- `<Aggregate>`: `<what it owns, what it may not reach into>`

## External systems of record

| Data | Owner | How we read it |
|---|---|---|
| `<Customer fragility flag>` | `<customer-service>` | `<REST, cached 5 min>` |
