-- A workflow now always belongs to a project.
--
-- workflow.gitlab_project is deliberately kept for one version: it is the column the legacy API
-- still reads, and dropping it would break clients that have not migrated yet.

alter table workflow alter column project_id set not null;
