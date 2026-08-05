package io.quarkiverse.quarkus.scylladb.orm.it.model;

import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;

/**
 * Deliberately dependency-free so the test covers the {@code @Convert} codegen path
 * itself rather than a JSON library.
 */
public class SettingsConverter implements AttributeConverter<Settings, String> {

    @Override
    public String toCqlColumn(Settings value) {
        return value == null ? null : value.theme() + ":" + value.fontSize();
    }

    @Override
    public Settings toEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        int sep = value.lastIndexOf(':');
        return new Settings(value.substring(0, sep), Integer.parseInt(value.substring(sep + 1)));
    }
}
