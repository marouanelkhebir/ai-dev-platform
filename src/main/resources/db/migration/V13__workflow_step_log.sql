-- Full output of every workflow step: container command output plus the platform log of the step.
--
-- Stored gzipped in a bytea rather than as text: build output compresses to a few percent of its
-- size, and the payload is written once and read whole, so there is nothing to gain from keeping it
-- searchable in SQL. One row per step, dropped by the retention job on its own, shorter schedule.
create table workflow_step_log (
    id                 uuid primary key,
    workflow_id        uuid        not null references workflow (id) on delete cascade,
    step_id            uuid        not null references workflow_step (id) on delete cascade,
    sequence_number    integer     not null,
    content            bytea       not null,
    uncompressed_chars bigint      not null,
    compressed_bytes   bigint      not null,
    truncated          boolean     not null default false,
    created_at         timestamptz not null
);

-- One log per step: a retried run creates a new step, never a second log for the same one.
create unique index ux_workflow_step_log_step on workflow_step_log (step_id);
create index ix_workflow_step_log_workflow on workflow_step_log (workflow_id, sequence_number);
