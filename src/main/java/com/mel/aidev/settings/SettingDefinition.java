package com.mel.aidev.settings;

import java.util.List;

/**
 * Description of one editable setting.
 *
 * <p>The {@code key} is the Spring property name ({@code jira.base-url}), which is what makes the
 * screen and the {@code application.yml} file talk about the same thing: an override stored under
 * that key is layered on top of the packaged configuration by {@link SettingsService}.
 *
 * @param key Spring property name, canonical (lower case, dashed)
 * @param group section of the settings screen
 * @param label short human label
 * @param type editing type, drives the widget and the validation
 * @param help one line of context, shown under the field
 * @param restartRequired true when changing the value only takes effect after a restart
 * @param options allowed values, for {@link SettingType#ENUM}
 */
public record SettingDefinition(
        String key,
        SettingsGroup group,
        String label,
        SettingType type,
        String help,
        boolean restartRequired,
        List<String> options) {

    public SettingDefinition {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static SettingDefinition of(
            String key, SettingsGroup group, String label, SettingType type, String help) {
        return new SettingDefinition(key, group, label, type, help, false, List.of());
    }

    public static SettingDefinition restartRequired(
            String key, SettingsGroup group, String label, SettingType type, String help) {
        return new SettingDefinition(key, group, label, type, help, true, List.of());
    }

    public static SettingDefinition options(
            String key, SettingsGroup group, String label, String help, List<String> options) {
        return new SettingDefinition(key, group, label, SettingType.ENUM, help, false, options);
    }

    public boolean isSecret() {
        return type.isSecret();
    }
}
