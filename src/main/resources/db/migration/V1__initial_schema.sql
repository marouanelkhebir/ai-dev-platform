-- AI Dev Platform - initial schema.
--
-- Design notes:
--  * The workflow row is the single source of truth; agents, sandboxes and webhooks are stateless
--    with respect to it, which is what makes the service restartable mid-ticket.
--  * Agent artefacts are stored as text rather than jsonb: they are audit payloads that are read
--    whole and never queried by their internal structure, and text keeps the schema portable.
--  * Every side effect (agent call, tool call, LLM call) has its own table so that a ticket can be
--    audited end to end.

create table workflow (
    id                    uuid primary key,
    jira_ticket           varchar(64)  not null,
    gitlab_project        varchar(512) not null,
    base_branch           varchar(255) not null,
    branch                varchar(255),
    status                varchar(64)  not null,
    development_attempts  integer      not null default 0,
    pipeline_attempts     integer      not null default 0,
    review_attempts       integer      not null default 0,
    merge_request_iid     bigint,
    merge_request_url     varchar(1024),
    pipeline_id           bigint,
    commit_sha            varchar(64),
    failure_reason        text,
    pending_feedback      text,
    ticket_analysis       text,
    technical_plan        text,
    test_report           text,
    code_review           text,
    security_report       text,
    acceptance_report     text,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    claimed_at            timestamptz,
    version               bigint       not null default 0
);

create index ix_workflow_status on workflow (status);
create index ix_workflow_ticket on workflow (jira_ticket);
create index ix_workflow_project_branch on workflow (gitlab_project, branch);
create index ix_workflow_mr on workflow (gitlab_project, merge_request_iid);

-- At most one non-terminal workflow per ticket: a Jira label toggled twice must not open two
-- merge requests. Enforced by the database, not only by the service.
create unique index ux_workflow_active_ticket
    on workflow (jira_ticket)
    where status not in ('DONE', 'FAILED', 'CANCELLED', 'NEEDS_CLARIFICATION');

create table workflow_step (
    id              uuid primary key,
    workflow_id     uuid        not null references workflow (id) on delete cascade,
    sequence_number integer     not null,
    status_from     varchar(64) not null,
    status_to       varchar(64),
    started_at      timestamptz not null,
    finished_at     timestamptz,
    duration_ms     bigint,
    successful      boolean,
    detail          text,
    error           text
);

create index ix_workflow_step_workflow on workflow_step (workflow_id, sequence_number);

create table agent_execution (
    id            uuid primary key,
    workflow_id   uuid        not null references workflow (id) on delete cascade,
    agent         varchar(64) not null,
    model         varchar(128),
    attempt       integer     not null,
    started_at    timestamptz not null,
    finished_at   timestamptz,
    duration_ms   bigint,
    successful    boolean,
    system_prompt text,
    user_prompt   text,
    raw_output    text,
    parsed_output text,
    error         text
);

create index ix_agent_execution_workflow on agent_execution (workflow_id, started_at);

create table tool_execution (
    id                 uuid primary key,
    workflow_id        uuid         not null references workflow (id) on delete cascade,
    agent_execution_id uuid,
    tool_name          varchar(128) not null,
    arguments          text,
    result             text,
    successful         boolean      not null,
    duration_ms        bigint,
    created_at         timestamptz  not null
);

create index ix_tool_execution_workflow on tool_execution (workflow_id, created_at);
create index ix_tool_execution_agent on tool_execution (agent_execution_id);

create table llm_execution (
    id                uuid primary key,
    workflow_id       uuid,
    agent             varchar(64),
    model             varchar(128),
    prompt_tokens     integer,
    completion_tokens integer,
    total_tokens      integer,
    duration_ms       bigint,
    successful        boolean     not null,
    finish_reason     varchar(64),
    error             text,
    created_at        timestamptz not null
);

create index ix_llm_execution_workflow on llm_execution (workflow_id, created_at);
create index ix_llm_execution_model on llm_execution (model, created_at);

create table merge_request (
    id             uuid primary key,
    workflow_id    uuid         not null references workflow (id) on delete cascade,
    gitlab_project varchar(512) not null,
    iid            bigint       not null,
    url            varchar(1024),
    source_branch  varchar(255) not null,
    target_branch  varchar(255) not null,
    title          varchar(512),
    created_at     timestamptz  not null
);

create index ix_merge_request_workflow on merge_request (workflow_id);

create table test_result (
    id          uuid primary key,
    workflow_id uuid        not null references workflow (id) on delete cascade,
    attempt     integer     not null,
    successful  boolean     not null,
    total_tests integer     not null,
    failed_tests integer    not null,
    report_json text,
    created_at  timestamptz not null
);

create index ix_test_result_workflow on test_result (workflow_id, attempt);

create table review_result (
    id            uuid primary key,
    workflow_id   uuid        not null references workflow (id) on delete cascade,
    review_type   varchar(32) not null,
    attempt       integer     not null,
    decision      varchar(32) not null,
    findings_json text,
    created_at    timestamptz not null
);

create index ix_review_result_workflow on review_result (workflow_id, created_at);

-- Idempotency ledger: Jira and GitLab both retry deliveries.
create table webhook_event (
    id          uuid primary key,
    source      varchar(32)  not null,
    external_id varchar(256) not null,
    event_type  varchar(128),
    payload     text,
    received_at timestamptz  not null,
    constraint ux_webhook_event unique (source, external_id)
);

create index ix_webhook_event_received on webhook_event (received_at);
