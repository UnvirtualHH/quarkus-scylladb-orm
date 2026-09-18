package io.quarkiverse.quarkus.scylladb.orm.mapping;

import com.datastax.oss.driver.api.core.type.reflect.GenericType;

public final class KeyComponent<T> {
    private final String name;
    private final GenericType<T> type;
    private final T value;
    private final int ordinal;

    private KeyComponent(String name, GenericType<T> type, T value, int ordinal) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.ordinal = ordinal;
    }

    public static <T> KeyComponent<T> of(String name, GenericType<T> type, T value, int ordinal) {
        return new KeyComponent<>(name, type, value, ordinal);
    }

    public String name() {
        return name;
    }

    public GenericType<T> type() {
        return type;
    }

    public T value() {
        return value;
    }

    public int ordinal() {
        return ordinal;
    }
}
