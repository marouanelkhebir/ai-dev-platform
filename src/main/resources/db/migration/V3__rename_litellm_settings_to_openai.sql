-- Preserve existing administrator-provided credentials while removing the LiteLLM configuration names.
update platform_setting
set setting_key = 'ai.openai.base-url'
where setting_key = 'ai.litellm.base-url'
  and not exists (select 1 from platform_setting where setting_key = 'ai.openai.base-url');

update platform_setting
set setting_key = 'ai.openai.api-key'
where setting_key = 'ai.litellm.api-key'
  and not exists (select 1 from platform_setting where setting_key = 'ai.openai.api-key');

update platform_setting
set setting_key = 'ai.openai.timeout'
where setting_key = 'ai.litellm.timeout'
  and not exists (select 1 from platform_setting where setting_key = 'ai.openai.timeout');

update platform_setting
set setting_key = 'ai.openai.max-retries'
where setting_key = 'ai.litellm.max-retries'
  and not exists (select 1 from platform_setting where setting_key = 'ai.openai.max-retries');

update platform_setting
set setting_key = 'ai.openai.log-requests'
where setting_key = 'ai.litellm.log-requests'
  and not exists (select 1 from platform_setting where setting_key = 'ai.openai.log-requests');

update platform_setting
set setting_key = 'ai.openai.log-responses'
where setting_key = 'ai.litellm.log-responses'
  and not exists (select 1 from platform_setting where setting_key = 'ai.openai.log-responses');

delete from platform_setting
where setting_key like 'ai.litellm.%';
