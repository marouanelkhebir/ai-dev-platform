-- The project owns everything a workflow needs to run: its GitLab repository, its Jira project, its
-- sandbox image and its execution configuration.
--
-- Design notes:
--  * gitlab_project is deliberately NOT unique. Two projects may target the same repository with
--    different configurations (target branch, image, commands); that is exactly what cloning is for.
--  * Commands are stored as JSON argv arrays, never as shell strings: CommandGuard forbids spawning
--    a shell, so a shell string could never be executed anyway.
--  * project_id is added nullable here; V9 backfills it and V10 makes it mandatory.

create table project (
    id                    uuid primary key,
    name                  varchar(128) not null,
    description           text,
    gitlab_project        varchar(512) not null,
    jira_project_key      varchar(32),
    docker_image          varchar(512),
    default_branch        varchar(255),
    branch_prefix         varchar(64),
    protected_branches    text,
    build_command         text,
    test_command          text,
    lint_command          text,
    retention_days        integer,
    active                boolean      not null default true,
    archived_at           timestamptz,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0
);

create unique index ux_project_name on project (lower(name));
create index ix_project_gitlab on project (gitlab_project);
create index ix_project_jira_key on project (jira_project_key) where jira_project_key is not null;

-- Non-sensitive variables injected into the sandbox environment. Values are visible to the agents,
-- so the service refuses anything that looks like a credential; secrets stay in platform_setting.
create table project_variable (
    id         uuid          primary key,
    project_id uuid          not null references project (id) on delete cascade,
    name       varchar(128)  not null,
    value      varchar(2048) not null,
    created_at timestamptz   not null,
    constraint ux_project_variable unique (project_id, name)
);

-- Model pinned per logical role. The platform allowlist (ai.models.allowed) bounds what may be
-- pinned here; an agent never selects its own model.
create table project_model (
    project_id uuid         not null references project (id) on delete cascade,
    model_role varchar(32)  not null,
    model_name varchar(128) not null,
    primary key (project_id, model_role)
);

alter table workflow add column project_id    uuid references project (id);
-- Snapshot of the configuration used at launch. Changing the project later must not rewrite the
-- history of a workflow that already ran.
alter table workflow add column launch_config text;
-- The image actually started, kept out of the snapshot because it is filtered and audited.
alter table workflow add column sandbox_image varchar(512);
-- updated_at moves on every transition; durations need a stable terminal timestamp.
alter table workflow add column finished_at   timestamptz;
alter table workflow add column archived_at   timestamptz;
alter table workflow add column purged_at     timestamptz;
alter table workflow add column audit_summary text;

create index ix_workflow_project_created on workflow (project_id, created_at desc);
create index ix_workflow_retention on workflow (finished_at) where purged_at is null;
