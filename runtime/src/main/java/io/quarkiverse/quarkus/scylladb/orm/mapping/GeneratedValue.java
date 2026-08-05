package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedValue {
    Strategy strategy() default Strategy.UUID;

    enum Strategy {
        /** A random UUID is assigned on write when the field is still null. */
        UUID
        // SEQUENCE was declared here but never implemented - it was silently ignored,
        // so an entity using it got no generated value at all. Cassandra has no
        // sequences; use UUID, or assign the value yourself.
    }
}