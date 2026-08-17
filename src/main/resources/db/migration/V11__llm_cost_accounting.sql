-- Cost accounting per project.
--
-- Design notes:
--  * project_id is denormalised on llm_execution. The table has no foreign key to workflow, which
--    is what lets the cost history survive both the deletion of a workflow and the purge of its
--    details by the retention job.
--  * cost_micros is computed and frozen when the row is written, from the price in force at that
--    moment. Recomputing later would silently rewrite past months every time a tariff changes.

alter table llm_execution add column project_id  uuid;
alter table llm_execution add column cost_micros bigint;

create index ix_llm_execution_project on llm_execution (project_id, created_at);

update llm_execution l
   set project_id = w.project_id
  from workflow w
 where l.workflow_id = w.id
   and l.project_id is null;

-- Tariffs, in micro-units of the currency per 1000 tokens. A model absent from this table is not
-- an error: its calls are recorded with a null cost and reported as "unpriced" by the dashboard,
-- which is honest, unlike a total that silently ignores them.
create table model_price (
    model                    varchar(128) primary key,
    prompt_micros_per_1k     bigint       not null,
    completion_micros_per_1k bigint       not null,
    currency                 varchar(3)   not null default 'USD',
    updated_at               timestamptz  not null
);
