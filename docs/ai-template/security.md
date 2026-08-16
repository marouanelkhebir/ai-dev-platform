# Security — <service name>

Copy to `.ai/security.md`. Read by the security agent.

## Data classification

| Data | Classification | Rule |
|---|---|---|
| `<IBAN>` | `<confidential>` | `<never logged, masked in API responses>` |
| `<Customer id>` | `<internal>` | `<never in a URL path>` |

## Authentication and authorisation

- `<how a caller is authenticated>`
- `<where the ownership check lives, and the helper to use>`
- `<roles and what each may do>`

## Known sensitive areas

- `<e.g. the /accounts/{id}/statements endpoint is the one place where an ownership bug leaks data>`

## Scanners

- SAST, dependency scanning and secret detection run in the pipeline.
- Findings must be fixed or explicitly justified; a suppression needs a comment naming the reason.
