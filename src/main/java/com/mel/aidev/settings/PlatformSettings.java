package com.mel.aidev.settings;

import com.mel.aidev.config.AiProperties;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.config.JiraProperties;
import com.mel.aidev.config.PlatformProperties;
import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.config.WorkflowProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Read access to the effective configuration.
 *
 * <p>Collaborators depend on this rather than on the {@code @ConfigurationProperties} beans: the
 * records are immutable snapshots, and a component holding one directly would keep serving the
 * values read at startup after an administrator changed them on the settings screen. Every accessor
 * returns the current values, so it must be called at the point of use rather than cached in a
 * field.
 *
 * <p>{@link #version()} increases on every change, which is what lets the components that must cache
 * something expensive — an HTTP client, a chat model — know when their cache is stale.
 */
public interface PlatformSettings {

    JiraProperties jira();

    GitLabProperties gitlab();

    AiProperties ai();

    WorkflowProperties workflow();

    SandboxProperties sandbox();

    PlatformProperties platform();

    /** Incremented on every applied change. */
    long version();

    /** Fixed settings, for tests and for anything that must not observe a change mid-flight. */
    static Builder builder() {
        return new Builder();
    }

    /** Builder of a fixed {@link PlatformSettings}; unset sections fall back to the code defaults. */
    final class Builder {

        private JiraProperties jira;
        private GitLabProperties gitlab;
        private AiProperties ai;
        private WorkflowProperties workflow;
        private SandboxProperties sandbox;
        private PlatformProperties platform;

        private Builder() {}

        public Builder jira(JiraProperties value) {
            this.jira = value;
            return this;
        }

        public Builder gitlab(GitLabProperties value) {
            this.gitlab = value;
            return this;
        }

        public Builder ai(AiProperties value) {
            this.ai = value;
            return this;
        }

        public Builder workflow(WorkflowProperties value) {
            this.workflow = value;
            return this;
        }

        public Builder sandbox(SandboxProperties value) {
            this.sandbox = value;
            return this;
        }

        public Builder platform(PlatformProperties value) {
            this.platform = value;
            return this;
        }

        public PlatformSettings build() {
            return new Fixed(
                    jira == null ? defaults("jira", JiraProperties.class) : jira,
                    gitlab == null ? defaults("gitlab", GitLabProperties.class) : gitlab,
                    ai == null ? defaults("ai", AiProperties.class) : ai,
                    workflow == null ? defaults("workflow", WorkflowProperties.class) : workflow,
                    sandbox == null ? defaults("sandbox", SandboxProperties.class) : sandbox,
                    platform == null ? new PlatformProperties(null) : platform);
        }

        /** Instance built from an empty environment: every field takes the fallback coded in the record. */
        private static <T> T defaults(String prefix, Class<T> type) {
            return new Binder(new MapConfigurationPropertySource()).bindOrCreate(prefix, type);
        }
    }

    /** Immutable {@link PlatformSettings}. */
    record Fixed(
            JiraProperties jira,
            GitLabProperties gitlab,
            AiProperties ai,
            WorkflowProperties workflow,
            SandboxProperties sandbox,
            PlatformProperties platform)
            implements PlatformSettings {

        @Override
        public long version() {
            return 0L;
        }
    }
}
