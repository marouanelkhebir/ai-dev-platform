# ai-dev-platform

An autonomous team of LLM agents that takes a Jira ticket and produces a tested, reviewed GitLab
merge request. **The merge itself stays manual.**

Java 21 · Spring Boot 3.3 · Maven · LangChain4j · PostgreSQL · Docker. No Python anywhere in the
orchestration.

---

## Table of contents

1. [What it does](#1-what-it-does)
2. [Architecture](#2-architecture)
3. [Why this design](#3-why-this-design)
4. [Running it locally](#4-running-it-locally)
5. [Configuring the OpenAI-compatible API](#5-configuring-the-openai-compatible-api)
6. [Configuring Jira](#6-configuring-jira)
7. [Configuring GitLab](#7-configuring-gitlab)
8. [Creating the technical accounts and tokens](#8-creating-the-technical-accounts-and-tokens)
9. [Security model](#9-security-model)
10. [Repository rules: the `.ai/` directory](#10-repository-rules-the-ai-directory)
11. [A complete run: BANK-1245](#11-a-complete-run-bank-1245)
12. [REST API](#12-rest-api)
13. [Observability](#13-observability)
14. [Extending: a new agent](#14-extending-a-new-agent)
15. [Extending: a new tool](#15-extending-a-new-tool)
16. [Onboarding a new repository](#16-onboarding-a-new-repository)
17. [Testing](#17-testing)
18. [Known limits](#18-known-limits)

---

## 1. What it does

```
JIRA (label agent-ready)
  │
  ▼
ANALYZE_JIRA ──ambiguity──► AI_NEEDS_CLARIFICATION (human)
  │
  ▼
ARCHITECT  (read-only plan)
  │
  ▼
DEVELOP  ◄──────────────────────────┐
  │                                 │
  ▼                                 │
LOCAL_TESTS ──fail──────────────────┤
  │                                 │
  ▼                                 │
SECURITY_REVIEW ──REQUEST_CHANGES───┘
  │       (nothing is pushed until this gate approves)
  ▼
PUSH → CREATE_MR
  │
  ▼
DONE   (a human reviews and merges, manually)
```

Both loops are bounded (3 attempts by default). When a budget is exhausted the ticket moves to
`AI_FAILED` with the work left in place and a report attached, so a human can finish it instead of
starting over.

The `CODE_REVIEW`, `ACCEPTANCE` and `WAITING_HUMAN_APPROVAL` states exist in `WorkflowStatus` and
their steps are implemented in the engine, but **the current version never transitions into them**:
`CREATING_MERGE_REQUEST` goes straight to `DONE`. The reviewer and acceptance agents therefore do
not run on a real ticket yet.

## 2. Architecture

```
ai-dev-platform/
├── pom.xml
├── docker-compose.yml
├── Dockerfile                       production image of the platform
├── .gitlab-ci.yml
├── docker/sandbox/                  image the tickets are developed in
├── docs/ai-template/                templates for the .ai/ directory of your repositories
└── src/main/java/com/company/aidev/
    ├── AiDevPlatformApplication.java
    ├── agent/                       the 7 agents + AgentSupport + MavenOutputParser
    ├── api/                         REST controllers, DTOs, error handling
    ├── config/                      @ConfigurationProperties records, HTTP clients, executors
    ├── domain/                      typed results: TicketAnalysis, TechnicalPlan, CodeReview, ...
    ├── git/                         GitOperations — clone, commit, push (platform-only)
    ├── gitlab/                      GitLabClient, webhook, models
    ├── jira/                        JiraClient, webhook, ADF conversion, models
    ├── llm/                         OpenAI-compatible API access, model roles, output parsing, prompts, audit
    ├── observability/               Micrometer meters, MDC helper
    ├── persistence/                 entities and repositories
    ├── rules/                       loader of the .ai/ directory
    ├── sandbox/                     SandboxManager, DockerSandboxManager, command and path guards
    ├── security/                    branch policy, webhook auth, API key, secret redaction
    ├── tool/                        the tools handed to the agents
    └── workflow/                    the engine, the state machine, the scheduler
```

### Differences from the layout suggested in the brief, and why

| Change | Reason |
|---|---|
| `git/GitOperations` split out of `tool/GitTools` | Cloning, committing and pushing are privileged. Keeping them out of the tool set means the developer agent is *structurally* unable to push, rather than merely instructed not to. |
| `llm/` package added | Model selection, prompt loading, structured-output parsing and LLM audit are one concern and are used by every agent. |
| `rules/` package added | Loading `.ai/*` is a repository concern, not an agent concern; agents receive `RepositoryRules`, already loaded. |
| `agent/MavenOutputParser` | Test counts are parsed from the build log deterministically. See below. |
| `workflow/WorkflowStateStore` | Isolates the short transactions around a long-running step. |
| `security/` holds real enforcement | `BranchPolicy`, `WebhookAuthenticator`, `SecretRedactor`, `ApiKeyFilter` — each backed by tests. |

## 3. Why this design

### LangChain4j Agentic, LangGraph4j, or neither?

**Decision: LangChain4j `AiServices` for the agents, a hand-written persisted state machine for the
orchestration. No LangGraph4j.**

The reasoning:

- **What LangChain4j gives us and we use**: declarative AI services, tool calling with typed Java
  methods, an OpenAI-compatible client, and chat model listeners for token
  accounting. That is exactly the agent-level plumbing we want, and `AgentSupport` is the only class
  that touches it.
- **What the workflow orchestration libraries give us and we do not need**: in-memory composition of
  agents (sequential, loop, parallel). Our workflow is not in-memory. It spans **hours or days**: it
  stops at `WAITING_PIPELINE` until a GitLab webhook arrives, and at `WAITING_HUMAN_APPROVAL` until a
  human acts. A graph held in a JVM object dies with the pod. Everything that matters — the state,
  the artefacts, the attempt counters — has to be in PostgreSQL anyway.
- Once the state lives in the database, the "graph" is a `switch` over an enum plus a row update. It
  is 300 lines, it is fully covered by `WorkflowEngineTest`, and a new state is one enum constant and
  one case. Adding LangGraph4j on top would mean persisting *its* state as well, so the same problem
  plus a dependency.

**When to revisit**: if you later want several agents debating in parallel *inside* a single step
(for example three reviewers voting), that is exactly what the agentic composition APIs are for, and
it plugs in at the agent level without touching the engine.

### Test results are not produced by a model

`MavenOutputParser` reads the totals and the failing tests from the real build log. Asking an LLM
"how many tests failed" is how a workflow ends up opening a merge request on a red build. The model
is only asked the question it is good at: *which acceptance criteria have no test*.

### Structured outputs are parsed defensively

`StructuredOutputParser` extracts the first balanced JSON value from the answer, ignoring markdown
fences and surrounding prose, and `AgentSupport` retries once with a repair instruction. Self-hosted
models behind vLLM do not reliably honour `response_format`, and this keeps the platform working
across model swaps.

### The domain enforces what the prompt only asks for

A reviewer that approves while reporting a `BLOCKER` is corrected by `CodeReview.normalized()`. A
criterion marked `PASS` with no evidence becomes `NOT_VERIFIABLE` in the record constructor. The
acceptance report is realigned on the ticket's own criteria list, so a model that silently drops one
cannot produce "4/4 covered". Prompts are guidance; invariants are code.

### Maven dependencies retained

| Dependency | Why |
|---|---|
| `spring-boot-starter-web`, `-data-jpa`, `-validation`, `-actuator`, `-aop` | API, persistence, config validation, health and metrics, Resilience4j aspects |
| `dev.langchain4j:langchain4j` + `langchain4j-open-ai` (1.0.1) | `AiServices`, tool calling, OpenAI-compatible client |
| `docker-java-core` + `docker-java-transport-httpclient5` (3.4.0) | Sandbox containers over the Docker API — no shell out to the `docker` CLI |
| `commons-compress` | Streams files in and out of a container as tar, avoiding shell quoting entirely |
| `flyway-core` + `flyway-database-postgresql` | Versioned schema |
| `postgresql` | Workflow state and the full audit trail |
| `resilience4j-spring-boot3` | Retry and circuit breaker on Jira and GitLab |
| `micrometer-registry-prometheus` | The `ai_*` metrics |
| `logstash-logback-encoder` | JSON logs with the workflow MDC |
| `springdoc-openapi` | API documentation |
| `wiremock-standalone`, `testcontainers`, `h2` (test) | Client contract tests, real PostgreSQL for the migration, fast context tests |

**Redis is not used.** It was on the shortlist for locking and idempotency, and neither needs it:
concurrency is handled by a `claimed_at` column plus JPA optimistic locking, and webhook idempotency
by a unique constraint. Adding Redis would add an operational dependency and a second source of
truth for no gain. If you later run many instances and want a shared rate limiter across pods, that
is the moment to introduce it.

## 4. Running it locally

Prerequisites: JDK 21+, Maven 3.9+, Docker, and access to an OpenAI-compatible API.

```bash
# 1. Build the sandbox images used according to the detected repository type
docker build -t ai-dev-sandbox:21 docker/sandbox
docker build -t ai-dev-sandbox-angular:22 docker/sandbox-angular

# 2. Configure
cp .env.example .env
# fill in PLATFORM_API_KEY, OPENAI_BASE_URL, OPENAI_API_KEY, JIRA_* and GITLAB_*

# 3. Start PostgreSQL and the platform
docker compose up --build
```

Bring the stack up **as a whole**. Starting the platform alone leaves it with no database to resolve.

Without Docker Compose, for development against a locally run application:

```bash
docker run -d --name aidev-pg -e POSTGRES_DB=aidev -e POSTGRES_USER=aidev \
  -e POSTGRES_PASSWORD=aidev -p 5432:5432 postgres:16-alpine
```

```bash
mvn spring-boot:run
```

Do not leave that standalone container running while also using Docker Compose: it holds host port
5432 and the Compose `postgres` service will fail to start. Either stop it (`docker rm -f aidev-pg`)
or set `DB_HOST_PORT=5433` in `.env`.

Check it is alive:

```bash
curl -s localhost:8080/actuator/health | jq
```

API documentation: `http://localhost:8080/swagger-ui.html`.

### Troubleshooting

**`UnknownHostException: postgres` at startup, container exits with code 1**

The platform container cannot resolve the database service, which means it is not on the same
network as a running `postgres`. Identify which case you are in:

```bash
docker compose ps -a
```

```bash
docker network inspect ai-dev-platform_aidev --format '{{range .Containers}}{{.Name}} {{end}}'
```

| Cause | Fix |
|---|---|
| The platform was started alone (`up ai-dev-platform`, `--no-deps`) | `docker compose up` — the whole stack |
| Another `postgres` holds host port 5432, so this project's never started | `docker rm -f aidev-pg`, or set `DB_HOST_PORT=5433` in `.env` |
| Containers left over from an earlier project name, on another network | `docker compose down --remove-orphans` then `docker compose up --build` |
| Legacy `docker-compose` v1 was used | Use `docker compose` (v2): v1 ignores `name:` and the `depends_on` conditions |

The application deliberately fails fast rather than starting without a database — an orchestrator
with no state store would silently lose workflows. The Compose services carry
`restart: unless-stopped` so a transient startup race resolves itself.

**`No qualifying bean` or a validation error on a `@ConfigurationProperties` record**

A required credential is missing. `OPENAI_API_KEY`, `JIRA_API_TOKEN` and `GITLAB_API_TOKEN` have no
default on purpose: a blank token must fail at startup, not at the first push to GitLab.

## 5. Configuring the OpenAI-compatible API

At first start, open the platform settings and enter the API base URL and API token supplied by your
provider. They are stored as platform settings; the token is encrypted and never returned by the API.

Four **logical roles** are mapped to model names:

```yaml
ai:
  openai:
    base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
    api-key: ${OPENAI_API_KEY}
    timeout: PT5M
  models:
    analysis: reasoning-model   # ticket analysis, planning, acceptance
    coding:   coder-model       # implementation and tests
    review:   reasoning-model   # code review, security review
    fast:     fast-model        # short utility calls
```

Each agent picks a role, and can override the sampling settings:

```yaml
ai:
  agents:
    developer:
      model-role: CODING
      temperature: 0.1
      max-tokens: 16384
      max-tool-calls: 120
    reviewer:
      model-role: REVIEW
      temperature: 0.0
```

For container deployments, provide `OPENAI_BASE_URL` and `OPENAI_API_KEY`. The API URL must include
the provider's version path when required (for example `/v1`).

**Sizing note.** The coding model needs a large context: the developer agent sends the plan, the
repository rules and the files it reads. 32k is a practical minimum, 128k is comfortable.

## 6. Configuring Jira

### Trigger

A workflow starts only when a ticket **opts in**, via a label or a status:

```yaml
jira:
  trigger:
    label: agent-ready
    status: READY_FOR_AI
```

The ticket must also carry a label naming the target repository:

```
gitlab-project:bank/customer-management
```

Keeping the mapping on the ticket means a human can always see why the platform pushed where it did.

### Statuses the platform writes back

| Status | When |
|---|---|
| `AI_IN_PROGRESS` | analysis succeeded, work started |
| `AI_NEEDS_CLARIFICATION` | the ticket is ambiguous or has no acceptance criteria |
| `AI_READY_FOR_REVIEW` | all gates passed, a human is expected |
| `AI_FAILED` | a retry budget was exhausted |

Create these statuses and the transitions into them in your Jira workflow. If a transition is
missing the platform logs a warning and carries on — a Jira misconfiguration must not lose a merge
request.

### Acceptance criteria

Read from the custom fields listed in `jira.acceptance-criteria-fields`, and, when those are empty,
parsed from an "Acceptance criteria" section of the description. Both paths are covered by
`RestJiraClientIT`.

### Webhook

Create a Jira webhook pointing at:

```
POST https://ai-dev-platform.company.com/webhooks/jira
```

Events: *issue updated*. Add a header `X-Webhook-Token` with the value of `JIRA_WEBHOOK_SECRET`.
A request without a valid token is rejected with 401.

## 7. Configuring GitLab

```yaml
gitlab:
  base-url: https://gitlab.company.com
  api-token: ${GITLAB_API_TOKEN}
  branch-prefix: "ai/"
  default-target-branch: main
  protected-branches: [main, master, develop, release, production]
  merge-request-label: AI-GENERATED
```

### Webhook

Per project, or at group level:

```
POST https://ai-dev-platform.company.com/webhooks/gitlab
```

Triggers: **Pipeline events** and **Merge request events**. Secret token: `GITLAB_WEBHOOK_SECRET`
(sent by GitLab as `X-Gitlab-Token`).

Pipeline events on branches outside `ai/` are ignored. Merge request events are acted on only for
`approved` and `merge`.

A lost webhook is not fatal: `WorkflowScheduler` polls pipelines that have been waiting longer than
`gitlab.pipeline.poll-interval`, and fails the workflow past `gitlab.pipeline.timeout`.

### Security reports

If your projects include the GitLab security templates, the platform reads
`gl-sast-report.json`, `gl-dependency-scanning-report.json` and `gl-secret-detection-report.json`
from the job artifacts and hands them to the security agent. The agent interprets them; it does not
replace them.

## 8. Creating the technical accounts and tokens

Two dedicated accounts. Never a personal account: the audit trail must say "the platform did this".

### `jira-ai-bot`

1. Create the Atlassian account, give it Browse/Comment/Transition on the relevant projects only.
2. https://id.atlassian.com/manage-profile/security/api-tokens → **Create API token**.
3. Set `JIRA_EMAIL` and `JIRA_API_TOKEN`.

Permissions to grant: browse projects, add comments, transition issues. **Not**: delete issues,
administer projects, manage webhooks.

### `gitlab-ai-bot`

1. Create the user, add it as **Developer** on the repositories in scope (not Maintainer).
2. Create a Personal Access Token with scopes `api`, `read_repository`, `write_repository`.
3. Set `GITLAB_API_TOKEN`.

Then, per project, **Settings → Repository → Protected branches**:

- `main`, `master`, `develop`, `release/*`: *Allowed to push* = **No one**, *Allowed to merge* =
  Maintainers. The bot is a Developer, so it cannot touch them.
- Add a protected branch pattern `ai/*` with *Allowed to push* = Developers, so the bot can push its
  own branches.

The bot must **not** have: Maintainer or Owner, permission to merge protected branches, permission
to delete repositories, access to production CI/CD variables, or membership in any group that grants
production deployment.

### Storing the secrets

Environment variables from your secret manager (Vault, GitLab CI/CD variables marked *masked* and
*protected*, or Kubernetes secrets). Nothing in this repository reads a token from a file, and
`.env` is gitignored.

### Docker socket permissions

The Compose service runs as root because Docker Desktop for macOS exposes
`/var/run/docker.sock` as writable only by its owner. A supplementary group therefore cannot
reliably grant the unprivileged application user access, and workflows would fail later with
`Permission denied` while creating their sandbox.

A raw Docker socket already grants host-equivalent container control. In production, use a
restricted Docker socket proxy and run the platform with only the permissions it needs.

## 9. Security model

The controls that actually stop something, and where they are enforced:

| Threat | Control | Enforced in |
|---|---|---|
| Command injection from model output | Commands are `List<String>` passed to `execCreate`; **no shell is ever spawned** | `DockerSandboxManager`, `CommandGuard` |
| Arbitrary binaries | Executable allowlist; `sh`, `bash`, `env` explicitly rejected | `CommandGuard` |
| Path traversal | Every model-provided path is normalised and must stay inside the repository | `WorkspacePaths` |
| Agent editing its own rules | `.ai/`, `.git/` and `.gitlab-ci.yml` are unwritable | `WorkspacePaths.assertWritable` |
| Push to a protected branch | Branch must match `ai/*` and must not be protected, checked before push **and** before merge request creation | `BranchPolicy` |
| Agent merging its own work | No merge method exists in `GitLabClient` | absence by design |
| Token leaking into the workspace | GitLab credentials are passed as `GIT_CONFIG_*` env vars, never in the remote URL | `GitOperations` |
| Token leaking into logs or audit rows | Every stored prompt, output, tool argument and error is redacted | `SecretRedactor` |
| Unauthenticated workflow creation | Static API key on `/api/**`, constant-time comparison | `ApiKeyFilter` |
| Forged webhooks | Shared secret, constant-time comparison, refuses to run when unconfigured | `WebhookAuthenticator` |
| Duplicate webhook delivery | Unique constraint on `(source, external_id)` | `WebhookIdempotencyService` |
| Two workflows on one ticket | Partial unique index on non-terminal workflows | `V1__initial_schema.sql` |
| Runaway agent loops | Bounded development, pipeline and review attempts | `WorkflowEngine` |
| Container escape / resource exhaustion | All capabilities dropped, `no-new-privileges`, memory, CPU and PID limits | `DockerSandboxManager` |
| Stale containers after a crash | Janitor removes containers past `sandbox.max-lifetime` | `WorkflowScheduler` |

The sandbox has **no production credentials**: only the variables listed under `sandbox.environment`
reach the container, and that list contains `MAVEN_OPTS` and `JAVA_TOOL_OPTIONS`.

In production, prefer a Docker socket proxy restricted to container operations over mounting
`/var/run/docker.sock` directly.

## 10. Repository rules: the `.ai/` directory

Any repository the platform may modify can carry an `.ai/` directory:

```
.ai/
├── agent-instructions.md   standing orders, read first
├── architecture.md         layers, dependencies, rules that must not be broken
├── domain.md               ubiquitous language and business invariants
├── coding-guidelines.md    style, patterns, what this team rejects in review
├── testing-guidelines.md   test layers, tooling, naming
├── security.md             data classification, authorisation rules, sensitive areas
└── commands.md             how to build and test here
```

Templates to copy are in [`docs/ai-template/`](docs/ai-template/).

Source priority when they disagree:

1. Jira acceptance criteria
2. the current code
3. `.ai/*`
4. OpenAPI specifications and ADRs
5. general documentation

The agents **never** modify these files — `WorkspacePaths.assertWritable` refuses writes under
`.ai/`. An agent able to edit its own constraints is an agent without constraints.

## 11. A complete run: BANK-1245

> *"When a customer becomes fragile, the active fee must be suspended."*

1. A developer adds the labels `agent-ready` and `gitlab-project:bank/customer-management` to
   **BANK-1245**.
2. Jira posts to `/webhooks/jira`. The token is verified, the delivery is recorded for idempotency,
   the ticket is found eligible, and a workflow is created.
3. **JiraAnalystAgent** reads the ticket, its comments and its linked issues, and produces a
   `TicketAnalysis`: objective, 4 acceptance criteria, impacted services, no ambiguity, risk `HIGH`.
   Jira moves to `AI_IN_PROGRESS`.
   *(Had it found an ambiguity, the run would stop here with `AI_NEEDS_CLARIFICATION` and a comment
   listing what needs clarifying.)*
4. **ArchitectAgent** explores the repository through read-only GitLab tools and produces a
   `TechnicalPlan`: files to change, ordered steps, tests to add, risks. It writes nothing.
5. A Docker sandbox is created for the ticket, the repository is cloned at `main`, and the branch
   `ai/BANK-1245` is created.
6. **DeveloperAgent** works in the sandbox with `readFile`, `writeFile`, `searchCode`, `listFiles`,
   `compile`, `runTests`, `runSingleTest`, `gitStatus`, `gitDiff`. It has no commit and no push tool.
7. **TestAgent** runs `./mvnw verify`. `MavenOutputParser` reads the real counts from the log. If the
   build is red, the failures go back to the developer agent as feedback — up to 3 attempts. Once
   green, the agent looks for uncovered acceptance criteria, adds the missing tests, and the build is
   re-run so the reported result describes what will actually be committed.
8. The platform commits `BANK-1245 Suspend the active fee when the customer becomes fragile` and
   pushes `ai/BANK-1245` (`--force-with-lease`, explicit refspec).
9. A merge request is opened against `main`, labelled `AI-GENERATED`, with Jira link, objective,
   changes, tests, and the review gates marked `PENDING`. The sandbox is destroyed. Jira gets a
   comment with the merge request URL.
10. GitLab CI runs. The pipeline webhook resumes the workflow. On failure, the logs of the failing
    jobs are sent back to the developer agent — up to 3 attempts.
11. **ReviewerAgent** receives only the ticket, the acceptance criteria, the diff and the `.ai/`
    rules. It reports findings with severities and decides `APPROVE` or `REQUEST_CHANGES`. A blocking
    finding sends the work back to development.
12. **SecurityAgent** reviews the diff and interprets the SAST, dependency scanning and secret
    detection reports from the pipeline, marking false positives with a justification.
13. **AcceptanceAgent** goes through the 4 criteria one by one and demands concrete evidence — a
    test name, a Cucumber scenario. A criterion with no evidence can never be `PASS`.
14. The full report is posted on the merge request:

    ```
    | Gate            | Result        |
    |-----------------|---------------|
    | Local tests     | PASS (47/47)  |
    | Code review     | APPROVE       |
    | Security review | APPROVE       |
    | Acceptance      | 4/4           |

    AC1 — When a customer becomes fragile, the active fee must be suspended
    Status: PASS
    Evidence:
    - FeeSuspensionServiceTest#shouldSuspendActiveFeeWhenCustomerBecomesFragile
    - Cucumber scenario: "Joint account - second holder becomes fragile"
    ```

15. Jira moves to `AI_READY_FOR_REVIEW`, the workflow waits in `WAITING_HUMAN_APPROVAL`.
16. A human reviews and merges. **The platform never merges.**

## 12. REST API

All `/api/**` calls require `X-Api-Key`.

```bash
# Start a workflow
curl -X POST localhost:8080/api/workflows \
  -H "X-Api-Key: $PLATFORM_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"jiraTicket":"BANK-1245","gitlabProjectId":"bank/customer-management"}'
```

```bash
# Full audit view: analysis, plan, reports, steps, agent executions
curl -s localhost:8080/api/workflows/$ID -H "X-Api-Key: $PLATFORM_API_KEY" | jq
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/workflows` | create and start |
| `GET` | `/api/workflows` | list, optional `?status=` |
| `GET` | `/api/workflows/{id}` | full audit view |
| `POST` | `/api/workflows/{id}/retry` | restart a stopped workflow with a fresh budget |
| `POST` | `/api/workflows/{id}/cancel` | stop it |
| `POST` | `/api/workflows/{id}/approve` | record the human decision (does **not** merge) |
| `POST` | `/webhooks/jira` | Jira events |
| `POST` | `/webhooks/gitlab` | pipeline and merge request events |

## 13. Observability

Prometheus at `/actuator/prometheus`:

```
ai_workflow_total{project}
ai_workflow_success_total{project}
ai_workflow_failed_total{project,reason}
ai_agent_execution_seconds{agent,model,success}
ai_agent_calls_total{agent,model,success}
ai_development_attempts{project}
ai_merge_request_created_total{project}
ai_pipeline_failed_total{project}
ai_review_rejected_total{project,type}
ai_llm_tokens{agent,model,direction}
ai_tool_calls_total{tool,success}
ai_sandbox_command_seconds{executable,success}
```

Logs carry `workflowId`, `jiraTicket`, `agent`, `model`, `gitlabProject`, `branch`, `mergeRequest`,
`attempt`, `durationMs`, `result` in the MDC. Activate the `json` profile for one JSON object per
line. API keys, tokens, secrets and customer data are redacted before anything is written.

Full audit in PostgreSQL: `workflow`, `workflow_step`, `agent_execution` (with the redacted prompts
and outputs), `tool_execution`, `llm_execution`, `merge_request`, `test_result`, `review_result`.

## 14. Extending: a new agent

Say you want a `PerformanceAgent` that reviews the diff for performance regressions.

**1. Add the enum constant** in `agent/AgentType.java`:

```java
PERFORMANCE(ModelRole.REVIEW),
```

**2. Write the prompt** in `src/main/resources/prompts/performance.md`. Describe the role, the rules
and — importantly — the exact JSON shape of the answer.

**3. Add the result record** in `domain/`, with `@JsonIgnoreProperties(ignoreUnknown = true)` and
invariants in the compact constructor.

**4. Write the agent**, following the shape of `ReviewerAgent`:

```java
@Component
public class PerformanceAgent {

    private final AgentSupport agentSupport;
    private final PromptLoader promptLoader;

    public PerformanceReport review(UUID workflowId, int attempt, TicketAnalysis analysis, String diff) {
        var execution = agentSupport.beginExecution(AgentType.PERFORMANCE, workflowId, attempt);
        var request = AgentRequest.withoutTools(
                AgentType.PERFORMANCE, workflowId, attempt,
                promptLoader.load("performance"), buildUserPrompt(analysis, diff));
        return agentSupport.execute(request, execution, PerformanceReport.class);
    }
}
```

`AgentSupport` handles model selection, tool binding, prompt persistence, JSON parsing with one
repair retry, metrics and MDC.

**5. Add the state** in `WorkflowStatus`, a case in `WorkflowEngine.executeStep`, and a step method.
The compiler will point at the `switch` — it is exhaustive on purpose.

**6. Configure the model** in `application.yml` under `ai.agents.performance`.

**7. Test it** in `WorkflowEngineTest`: a mock returning a rejection must send the work back to
development, and one returning an approval must let the workflow continue.

## 15. Extending: a new tool

Tools are plain Java objects with `@Tool` methods, created per agent execution and bound to one
sandbox.

```java
public class DependencyTools {

    private final SandboxManager sandboxManager;
    private final ToolExecutionRecorder recorder;
    private final ToolContext context;

    @Tool("Show the dependency tree of the project.")
    public String dependencyTree(@P("Group id to filter on, or empty for all") String groupId) {
        return recorder.record(context.workflowId(), context.agentExecutionId(),
                "dependencyTree", groupId, () -> {
            List<String> command = List.of("mvn", "-B", "-ntp", "dependency:tree");
            return sandboxManager.execute(context.sandbox(), command,
                    context.sandbox().repositoryPath(), Duration.ofMinutes(5)).toToolOutput(20_000);
        });
    }
}
```

Then hand it to the agents that need it — and only those — in their `List.of(...)` of tools.

Rules to respect:

- **Always wrap in `recorder.record(...)`.** It provides the audit row, the metric and the conversion
  of a failure into a message the model can act on instead of an exception that aborts the attempt.
- **Never build a shell string.** Commands are `List<String>`; the executable must be in
  `sandbox.allowed-executables`, and `CommandGuard` will reject anything else.
- **Never accept a raw path.** Use the sandbox methods, which route through `WorkspacePaths`.
- **Bound the output.** A 40 MB Maven log will blow the context window and the bill.
- **Give the smallest tool set that does the job.** The reviewer, security and acceptance agents have
  no tools at all, and that is a feature.

## 16. Onboarding a new repository

1. Add `gitlab-ai-bot` as **Developer** on the project.
2. Protect `main`/`master` (push: no one) and add the `ai/*` pattern (push: developers).
3. Add the GitLab webhook (pipeline + merge request events) with the shared secret.
4. Create the `.ai/` directory from [`docs/ai-template/`](docs/ai-template/) and fill it in. This is
   the single highest-leverage step: the quality of the output tracks the quality of `.ai/domain.md`
   and `.ai/architecture.md` closely.
5. Make sure the project builds with `./mvnw verify` inside the sandbox image. If it needs an
   internal Nexus, point `docker/sandbox/settings.xml` at it.
6. Try it on a small, well-specified ticket first, with clear acceptance criteria.
7. Read the merge request. The first few will tell you what is missing from `.ai/`.

## 17. Testing

```bash
mvn test      # unit tests
mvn verify    # + integration tests
```

- **Unit** — `StructuredOutputParserTest` (the shapes models actually produce), `CommandGuardTest`
  and `WorkspacePathsTest` (the injection and traversal surfaces), `MavenOutputParserTest` (real
  Surefire output), `BranchPolicyTest`, `SecretRedactorTest`, `DomainInvariantsTest`,
  `AcceptanceAgentAlignmentTest`, `MavenToolsTest`.
- **Workflow** — `WorkflowEngineTest` drives the whole state machine with every external system
  mocked: happy path, ambiguous ticket, bounded retry loops, pipeline failure feedback, rejected
  review, uncovered acceptance criteria, and the guarantee that nothing reaches a human until every
  gate agrees.
- **Simulated GitLab pipeline** — `WebhookIT` posts real pipeline and merge request payloads and
  asserts the workflow resumes, ignores foreign branches, and deduplicates redeliveries.
- **Client contracts** — `RestJiraClientIT` and `RestGitLabClientIT` run against WireMock, covering
  ADF conversion, acceptance criteria fallback, URL encoding of project paths, and 404 handling.
- **Schema** — `FlywayMigrationIT` runs the migration against a real PostgreSQL via Testcontainers
  and verifies the partial unique index. It is skipped when no Docker daemon is available.

## 18. Known limits

Stated plainly, because a platform that writes code should be honest about what it does not do.

- **A sandbox does not survive a restart.** If the pod dies mid-development, the workflow re-enters
  `DEVELOPING` with a fresh container and re-clones. Work already pushed is kept; work in progress in
  the container is lost. Bounded by the attempt counters.
- **The diff sent to the reviewers is truncated** at 120k characters. Very large changes are reviewed
  partially — and a change that large should be split anyway.
- **Merge conflicts are not resolved.** If `main` moves under an open merge request, a human rebases.
- **Multi-module builds** report the totals of the last Surefire summary. This is correct for
  single-module repositories and for the aggregate line; per-module attribution is not implemented.
- **Only Maven is supported.** Gradle would need a new `GradleTools` plus a new output parser.
- **The acceptance agent judges evidence, it does not execute it.** It checks that a named test
  exists and covers the criterion; it trusts the test itself to be meaningful.
- **Automatic merge is not implemented**, on purpose, and `workflow.allow-auto-merge` does nothing in
  this version. `GitLabClient` has no merge method at all.
