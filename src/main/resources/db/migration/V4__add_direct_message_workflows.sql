-- A direct request follows the same workflow as a Jira issue, without any Jira side effects.
alter table workflow add column source_message text;
