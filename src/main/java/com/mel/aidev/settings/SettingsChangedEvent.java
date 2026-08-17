package com.mel.aidev.settings;

import java.util.Set;

/**
 * Published once a change has been persisted and the new snapshot is live.
 *
 * <p>Components that cache something derived from the configuration do not need to listen: they
 * compare {@link PlatformSettings#version()} on use. The event exists for what has to react rather
 * than notice — logging, metrics, and anything holding a connection it must drop.
 *
 * @param version version of the snapshot that is now live
 * @param changedKeys settings that were written or reset
 * @param actor who applied the change, for the audit trail
 */
public record SettingsChangedEvent(long version, Set<String> changedKeys, String actor) {

    public SettingsChangedEvent {
        changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
    }
}
