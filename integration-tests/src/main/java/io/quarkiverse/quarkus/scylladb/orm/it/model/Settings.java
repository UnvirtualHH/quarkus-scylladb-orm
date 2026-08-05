package io.quarkiverse.quarkus.scylladb.orm.it.model;

/**
 * Value type persisted through an {@link SettingsConverter} — the payload for the
 * {@code @Convert} mapping path.
 */
public record Settings(String theme, int fontSize) {
}
