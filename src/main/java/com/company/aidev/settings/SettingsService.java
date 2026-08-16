package com.company.aidev.settings;

import com.company.aidev.config.AiProperties;
import com.company.aidev.config.GitLabProperties;
import com.company.aidev.config.JiraProperties;
import com.company.aidev.config.PlatformProperties;
import com.company.aidev.config.SandboxProperties;
import com.company.aidev.config.WorkflowProperties;
import com.company.aidev.persistence.entity.PlatformSettingEntity;
import com.company.aidev.persistence.repository.PlatformSettingRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective configuration of the platform, and the only writer of the settings screen.
 *
 * <p>Three layers, in increasing precedence: the values packaged in {@code application.yml}, the
 * environment ({@code .env} through Docker Compose, or the deployment's secret manager), and the
 * overrides stored in {@code platform_setting} by an administrator. A key that is absent from the
 * table therefore keeps its deployment value, and resetting a field on the screen simply deletes
 * the row.
 *
 * <p>Rebinding uses the same {@link Binder} Spring Boot uses at startup, so an override behaves
 * exactly like a property set in the file — including relaxed names, durations and lists.
 */
@Service
public class SettingsService implements PlatformSettings {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Internal rows, never editable from the API and never returned by it. */
    static final String ENCRYPTION_KEY_ROW = "__encryption-key";

    private final PlatformSettingRepository repository;
    private final ConfigurableEnvironment environment;
    private final ApplicationEventPublisher events;

    /** Decrypted overrides, keyed by canonical property name. */
    private volatile Map<String, String> overrides = Map.of();

    private volatile PlatformSettings.Fixed snapshot;
    private final AtomicLong version = new AtomicLong();
    private SettingsEncryptor encryptor;

    public SettingsService(
            PlatformSettingRepository repository,
            ConfigurableEnvironment environment,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.environment = environment;
        this.events = events;
        // A snapshot without the stored overrides, so that a component reading settings while the
        // context is still starting sees the packaged configuration rather than null.
        this.snapshot = bind(Map.of());
    }

    @PostConstruct
    @Transactional
    public void load() {
        this.encryptor = new SettingsEncryptor(resolveEncryptionKey());
        reload();
        ensureApiKey();
        log.info(
                "Settings loaded: {} override(s) on top of the packaged configuration, version {}",
                overrides.size(),
                version.get());
    }

    // ------------------------------------------------------------------ reading

    @Override
    public JiraProperties jira() {
        return snapshot.jira();
    }

    @Override
    public GitLabProperties gitlab() {
        return snapshot.gitlab();
    }

    @Override
    public AiProperties ai() {
        return snapshot.ai();
    }

    @Override
    public WorkflowProperties workflow() {
        return snapshot.workflow();
    }

    @Override
    public SandboxProperties sandbox() {
        return snapshot.sandbox();
    }

    @Override
    public PlatformProperties platform() {
        return snapshot.platform();
    }

    @Override
    public long version() {
        return version.get();
    }

    /** What the settings screen displays: one entry per editable key, secrets never included. */
    public List<SettingValue> values() {
        Binder binder = binder(overrides);
        List<SettingValue> values = new ArrayList<>();
        for (SettingDefinition definition : SettingsCatalog.all()) {
            String value = readAsString(binder, definition);
            boolean overridden = overrides.containsKey(definition.key());
            values.add(new SettingValue(
                    definition,
                    definition.isSecret() ? null : value,
                    value != null && !value.isBlank(),
                    overridden ? SettingSource.SETTINGS_SCREEN : sourceOf(definition.key()),
                    overridden ? null : originOf(definition.key())));
        }
        return values;
    }

    /** Which integrations are usable right now, and what is missing for the others. */
    public PlatformReadiness readiness() {
        return PlatformReadiness.of(snapshot);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Applies a partial change. A blank value resets the key to its deployment default.
     *
     * <p>Nothing is written until the whole change binds cleanly, so a typo in a duration cannot
     * leave the platform with half of a new configuration.
     *
     * @throws SettingsValidationException when a key is not editable or a value is malformed
     */
    @Transactional
    public synchronized long update(Map<String, String> changes, String actor) {
        if (changes == null || changes.isEmpty()) {
            return version.get();
        }
        List<String> errors = new ArrayList<>();
        Map<String, String> sanitized = new LinkedHashMap<>();

        changes.forEach((rawKey, rawValue) -> {
            String key = rawKey == null ? "" : rawKey.trim();
            Optional<SettingDefinition> definition = SettingsCatalog.find(key);
            if (definition.isEmpty()) {
                errors.add(key + ": unknown or non-editable setting");
                return;
            }
            String value = rawValue == null ? "" : rawValue.trim();
            String error = definition.get().type().validate(value, definition.get());
            if (error != null) {
                errors.add(key + ": " + error);
                return;
            }
            sanitized.put(key, value);
        });

        if (!errors.isEmpty()) {
            throw new SettingsValidationException("Invalid settings", errors);
        }

        Map<String, String> candidate = new LinkedHashMap<>(overrides);
        sanitized.forEach((key, value) -> {
            if (value.isEmpty()) {
                candidate.remove(key);
            } else {
                candidate.put(key, value);
            }
        });

        try {
            bind(candidate); // dry run: refuses a value the binder cannot convert
        } catch (RuntimeException e) {
            throw new SettingsValidationException(
                    "The new configuration could not be bound", List.of(rootMessage(e)));
        }

        sanitized.forEach((key, value) -> {
            if (value.isEmpty()) {
                repository.deleteById(key);
                return;
            }
            boolean secret = SettingsCatalog.isSecret(key);
            String stored = secret ? encryptor.encrypt(value) : value;
            PlatformSettingEntity entity = repository.findById(key).orElse(null);
            if (entity == null) {
                repository.save(new PlatformSettingEntity(key, stored, secret, actor));
            } else {
                entity.update(stored, secret, actor);
                repository.save(entity);
            }
        });
        repository.flush();

        reload();
        long applied = version.get();
        Set<String> changedKeys = new LinkedHashSet<>(sanitized.keySet());
        log.info("Settings updated by {}: {} (version {})", actor, changedKeys, applied);
        events.publishEvent(new SettingsChangedEvent(applied, changedKeys, actor));
        return applied;
    }

    /** Removes an override, so the key goes back to its {@code application.yml} or environment value. */
    @Transactional
    public long reset(String key, String actor) {
        return update(Map.of(key, ""), actor);
    }

    // ----------------------------------------------------------------- internals

    private void reload() {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (PlatformSettingEntity entity : repository.findAll()) {
            if (ENCRYPTION_KEY_ROW.equals(entity.getKey())) {
                continue;
            }
            if (!SettingsCatalog.isEditable(entity.getKey())) {
                // A key removed from the catalog by an upgrade: ignored rather than applied blindly.
                log.warn("Ignoring stored setting '{}': not part of the editable catalogue", entity.getKey());
                continue;
            }
            String value = entity.isEncrypted() ? encryptor.decrypt(entity.getValue()) : entity.getValue();
            if (value != null && !value.isBlank()) {
                loaded.put(entity.getKey(), value);
            }
        }
        this.overrides = Map.copyOf(loaded);
        this.snapshot = bind(this.overrides);
        this.version.incrementAndGet();
    }

    private PlatformSettings.Fixed bind(Map<String, String> currentOverrides) {
        Binder binder = binder(currentOverrides);
        return new PlatformSettings.Fixed(
                binder.bindOrCreate("jira", JiraProperties.class),
                binder.bindOrCreate("gitlab", GitLabProperties.class),
                binder.bindOrCreate("ai", AiProperties.class),
                binder.bindOrCreate("workflow", WorkflowProperties.class),
                binder.bindOrCreate("sandbox", SandboxProperties.class),
                binder.bindOrCreate("platform", PlatformProperties.class));
    }

    private Binder binder(Map<String, String> currentOverrides) {
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        sources.add(new MapConfigurationPropertySource(currentOverrides));
        ConfigurationPropertySources.get(environment).forEach(sources::add);
        return new Binder(sources, new PropertySourcesPlaceholdersResolver(environment));
    }

    /**
     * Generates an API key when the deployment provides none, so that a fresh install is reachable
     * without being open.
     */
    private void ensureApiKey() {
        if (snapshot.platform().hasApiKey()) {
            return;
        }
        String generated = SettingsEncryptor.generateKey();
        repository.save(new PlatformSettingEntity(
                "platform.api-key", encryptor.encrypt(generated), true, "bootstrap"));
        repository.flush();
        reload();
        log.warn(
                """

                        ****************************************************************
                        No API key was configured (platform.api-key / PLATFORM_API_KEY).
                        One has been generated for this installation:

                            {}

                        Use it to open the console at /settings.html, then set your own
                        key from the Plateforme section, or via PLATFORM_API_KEY.
                        ****************************************************************""",
                generated);
    }

    private String resolveEncryptionKey() {
        String configured = environment.getProperty("settings.encryption-key");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Optional<PlatformSettingEntity> stored = repository.findById(ENCRYPTION_KEY_ROW);
        if (stored.isPresent() && stored.get().getValue() != null) {
            return stored.get().getValue();
        }
        String generated = SettingsEncryptor.generateKey();
        repository.save(new PlatformSettingEntity(ENCRYPTION_KEY_ROW, generated, false, "bootstrap"));
        repository.flush();
        log.warn(
                "No SETTINGS_ENCRYPTION_KEY configured: a key was generated and stored in the database."
                        + " Secrets are encrypted at rest, but a dump contains both the key and the values."
                        + " Provide SETTINGS_ENCRYPTION_KEY from your secret manager in production.");
        return generated;
    }

    /** Reads one key back as text, whatever its declared type. */
    private String readAsString(Binder binder, SettingDefinition definition) {
        try {
            if (definition.type() == SettingType.LIST) {
                return binder.bind(definition.key(), Bindable.listOf(String.class))
                        .map(values -> String.join(", ", values))
                        .orElse(null);
            }
            return binder.bind(definition.key(), Bindable.of(String.class)).orElse(null);
        } catch (BindException e) {
            log.debug("Unable to read setting {} as text", definition.key(), e);
            return null;
        }
    }

    private SettingSource sourceOf(String key) {
        return findConfigurationSource(key)
                .map(name -> name.contains("systemEnvironment") || name.contains("environmentVariable")
                        ? SettingSource.ENVIRONMENT
                        : SettingSource.CONFIGURATION_FILE)
                .orElse(SettingSource.CODE_DEFAULT);
    }

    private String originOf(String key) {
        return findConfigurationSource(key).orElse(null);
    }

    private Optional<String> findConfigurationSource(String key) {
        return Optional.ofNullable(environment.getPropertySources().stream()
                .filter(source -> source.containsProperty(key)
                        || source.containsProperty(key.replace('.', '_').toUpperCase(java.util.Locale.ROOT)))
                .map(source -> source.getName())
                .findFirst()
                .orElse(null));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? error.toString() : current.getMessage();
    }

    /** Where a value comes from, shown as a badge next to each field. */
    public enum SettingSource {
        SETTINGS_SCREEN,
        ENVIRONMENT,
        CONFIGURATION_FILE,
        CODE_DEFAULT
    }

    /**
     * One row of the settings screen.
     *
     * @param value current value, always {@code null} for a secret
     * @param configured true when a value is set, the only thing reported about a secret
     */
    public record SettingValue(
            SettingDefinition definition,
            String value,
            boolean configured,
            SettingSource source,
            String origin) {}
}
