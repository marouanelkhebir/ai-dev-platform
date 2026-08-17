-- Backfills a project for every repository that already produced workflows.
--
-- These projects are created inactive: they exist so that the history stays readable and
-- project_id can become mandatory, not so that new work is started from them. An administrator
-- reviews and activates them from the projects screen.

insert into project (
    id, name, description, gitlab_project, jira_project_key, default_branch,
    active, archived_at, created_at, updated_at, version)
select
    gen_random_uuid(),
    'Historique — ' || grouped.gitlab_project,
    'Projet créé automatiquement lors de la migration V9 pour rattacher les workflows existants.',
    grouped.gitlab_project,
    grouped.jira_project_key,
    grouped.default_branch,
    false,
    now(),
    coalesce(grouped.first_seen, now()),
    now(),
    0
from (
    select
        w.gitlab_project as gitlab_project,
        min(w.created_at) as first_seen,
        -- Most frequent Jira key prefix of the group; direct requests (MSG-*) are ignored.
        (select split_part(inner_w.jira_ticket, '-', 1)
           from workflow inner_w
          where inner_w.gitlab_project = w.gitlab_project
            and inner_w.jira_ticket ~ '^[A-Z][A-Z0-9_]*-[0-9]+$'
            and inner_w.source_message is null
          group by split_part(inner_w.jira_ticket, '-', 1)
          order by count(*) desc, split_part(inner_w.jira_ticket, '-', 1)
          limit 1) as jira_project_key,
        -- Most frequent base branch of the group, used as the project default branch.
        (select inner_w.base_branch
           from workflow inner_w
          where inner_w.gitlab_project = w.gitlab_project
          group by inner_w.base_branch
          order by count(*) desc, inner_w.base_branch
          limit 1) as default_branch
    from workflow w
    group by w.gitlab_project
) grouped;

update workflow w
   set project_id = p.id
  from project p
 where w.project_id is null
   and p.gitlab_project = w.gitlab_project;

-- A workflow left without a project would make V10 fail with an opaque constraint error; failing
-- here says what is actually wrong.
do $$
declare orphans bigint;
begin
    select count(*) into orphans from workflow where project_id is null;
    if orphans > 0 then
        raise exception 'V9 left % workflow(s) without a project', orphans;
    end if;
end $$;
