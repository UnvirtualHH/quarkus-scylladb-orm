package io.quarkiverse.quarkus.scylladb.orm.mapping;

/**
 * One primary key column of an entity instance: its name, its value and its position in
 * the key.
 * <p>
 * Used to carry the column's {@code GenericType} as well. Nothing ever read it — the
 * repositories bind {@link #value()} against the prepared statement's own column
 * metadata — so every keyed read, delete and existence check built and threw away a
 * {@code GenericType} plus the {@code TypeToken} inside it, per key column. It also put a
 * {@code ...type.reflect} type into every generated mapper, which is not something code
 * meant to stay reflection-free should have to explain.
 */
public final class KeyComponent<T> {
    private final String name;
    private final T value;
    private final int ordinal;

    private KeyComponent(String name, T value, int ordinal) {
        this.name = name;
        this.value = value;
        this.ordinal = ordinal;
    }

    public static <T> KeyComponent<T> of(String name, T value, int ordinal) {
        return new KeyComponent<>(name, value, ordinal);
    }

    public String name() {
        return name;
    }

    public T value() {
        return value;
    }

    public int ordinal() {
        return ordinal;
    }
}
