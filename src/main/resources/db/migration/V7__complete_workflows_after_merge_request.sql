-- A merge request is now the terminal deliverable of the agent workflow.
-- Existing rows in this legacy state have already been pushed and have an open merge request.
update workflow
set status = 'DONE',
    updated_at = current_timestamp,
    claimed_at = null
where status = 'WAITING_PIPELINE';
