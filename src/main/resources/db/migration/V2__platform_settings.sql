-- Settings edited from the administration screen.
--
-- Only overrides are stored: a key absent from this table keeps the value coming from
-- application.yml or the environment. Deleting a row is therefore "reset to the deployment default",
-- and truncating the table restores the packaged configuration as a whole.
--
-- Secrets (tokens, keys, webhook shared secrets) are stored encrypted; the `encrypted` flag says
-- which form the value column holds so that the key can be rotated without guessing.

create table platform_setting (
    setting_key varchar(128) primary key,
    value       text,
    encrypted   boolean      not null default false,
    updated_at  timestamptz  not null,
    updated_by  varchar(128)
);
