# AI Dev Platform

AI Dev Platform is a Java service that orchestrates a team of LLM agents to turn a Jira ticket or a developer request into a tested merge request on GitLab, Bitbucket or GitHub. A human always reviews and merges the resulting merge request.

The platform provides a small web console, a REST API and an event stream to follow each workflow.

## What it does

For each workflow, the platform can:

1. analyse the Jira issue and ask for clarification when needed;
2. create a technical plan;
3. clone the target repository — GitLab, Bitbucket or GitHub, chosen per project — into an isolated Docker sandbox;
4. implement the change, run tests and perform a security review;
5. push an `ai/` branch and open a merge request (a pull request on Bitbucket and GitHub) after its gates pass.

Workflow state, execution details and model costs are persisted in PostgreSQL. Jira, the source-control providers and the LLM provider are configured with environment variables or from the settings screen.

## Architecture

```text
Jira or developer request
          |
          v
  analysis -> planning -> development -> tests -> security gate
                                                       |
                                                       v
                                    ai/ branch + merge request
                                                       |
                                                       v
                                            human review and merge
```

The application is built with Java 21, Spring Boot 3.3, Maven, PostgreSQL, Docker and LangChain4j. Sandboxed project work runs in dedicated Java, Angular or Python containers.

## Prerequisites

- JDK 21 and Maven 3.9+ (for running outside Docker)
- Docker Desktop or Docker Engine with Docker Compose v2
- Access to an OpenAI-compatible LLM API
- Jira credentials and, for each source-control provider you use, GitLab, Bitbucket or GitHub service credentials

## Quick start with Docker Compose

```bash
# Clone the project, then enter it
git clone https://github.com/<your-account>/ai-dev-platform.git
cd ai-dev-platform

# Build the sandbox images and create the local .env file
./init.sh

# Edit .env and provide at least PLATFORM_API_KEY and the OpenAI settings
# Then start PostgreSQL and the application
docker compose up --build
```

Open the console at [http://localhost:8080](http://localhost:8080). The API documentation is available at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html), and the health endpoint at [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health).

Stop the stack with `docker compose down`. Add `-v` only if you deliberately want to delete the local PostgreSQL data volume.

## Configuration

Copy the template before running the application:

```bash
cp .env.example .env
```

`.env` is intentionally ignored by Git. Never put real credentials in `.env.example`, `application.yml`, documentation or source code.

| Variable | Required | Description |
| --- | --- | --- |
| `PLATFORM_API_KEY` | Yes | Key required in the `X-Api-Key` header for `/api/**`. |
| `OPENAI_BASE_URL` | Yes | Base URL of the OpenAI-compatible API. |
| `OPENAI_API_KEY` | Yes | API key for the LLM provider. |
| `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` | For Jira | Jira connection settings. |
| `JIRA_WEBHOOK_SECRET` | For Jira webhooks | Shared secret used to authenticate Jira webhooks. |
| `GITLAB_BASE_URL`, `GITLAB_API_TOKEN` | For GitLab | GitLab connection settings. Use a minimally scoped bot token. |
| `GITLAB_WEBHOOK_SECRET` | For GitLab webhooks | Shared secret used to authenticate GitLab webhooks. |
| `BITBUCKET_BASE_URL`, `BITBUCKET_USERNAME`, `BITBUCKET_API_TOKEN` | For Bitbucket | Bitbucket Cloud connection settings, used by the projects whose provider is Bitbucket. |
| `GITHUB_BASE_URL`, `GITHUB_API_TOKEN` | For GitHub | GitHub connection settings, used by the projects whose provider is GitHub. The token needs `contents` and `pull requests` write access; on GitHub Enterprise Server the base URL is the API root, e.g. `https://ghe.company.com/api/v3`. |
| `DB_PASSWORD` | Optional locally | PostgreSQL password; Compose defaults to `aidev` for local development only. |
| `MODEL_ANALYSIS`, `MODEL_CODING`, `MODEL_REVIEW`, `MODEL_FAST` | Optional | Model names used for the platform's logical roles. |

For a public deployment, use a secrets manager or your deployment platform's protected secret store rather than committing a configuration file.

## Run without Docker Compose

Start a PostgreSQL 16 database, then export the connection settings and run Spring Boot:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/aidev
export DB_USERNAME=aidev
export DB_PASSWORD=aidev
export PLATFORM_API_KEY=change-me
export OPENAI_BASE_URL=https://your-llm-provider.example/v1
export OPENAI_API_KEY=your-key

mvn spring-boot:run
```

The workflow still requires the sandbox images. Build them first with `./init.sh`.

## Useful commands

```bash
mvn clean compile      # Compile
mvn test               # Run unit tests
mvn verify             # Run unit and integration tests, with JaCoCo output
docker compose up --build
```

## API authentication

All `/api/**` endpoints require the platform API key:

```bash
curl -H "X-Api-Key: $PLATFORM_API_KEY" \
  http://localhost:8080/api/projects
```

See the Swagger UI for request examples and the full endpoint reference.

## Debugging a run

Every step of a workflow stores its complete output — every command executed in the container, with
its exit code and duration, plus the platform log emitted during that step. It is stored gzipped in
`workflow_step_log` and served as plain text:

```bash
curl -H "X-Api-Key: $PLATFORM_API_KEY" \
  http://localhost:8080/api/workflows/$WORKFLOW_ID/steps/4/logs
```

The whole run, as a file:

```bash
curl -H "X-Api-Key: $PLATFORM_API_KEY" -o run.log.gz \
  "http://localhost:8080/api/workflows/$WORKFLOW_ID/logs?download=true"
```

The console exposes the same logs per step, with a download button. Secrets are redacted before the
log is written, a step is capped at `workflow.logs.max-chars-per-step` (the head and the tail are
kept when it overflows), and the logs of a finished workflow are deleted after
`workflow.logs.retention-days`, ahead of the audit payloads.

## Security notes

- Local credentials belong only in `.env`, which is excluded from Git and the Docker build context.
- Private keys, keystores, certificates with private material, local secret directories and common local configuration files are ignored.
- The application does not automatically merge code; a human merge remains required.
- The Docker socket is mounted for the trusted orchestrator so it can create sandboxes. Treat access to the deployed platform as privileged and use a restricted socket proxy in production.

## Repository onboarding

Each repository managed by the platform may contain an `.ai/` directory. The templates under [`docs/ai-template/`](docs/ai-template/) describe project architecture, commands, testing and security constraints for agents.

## License

No licence has been selected yet. Add a `LICENSE` file before inviting external contributors or publishing reusable code.
