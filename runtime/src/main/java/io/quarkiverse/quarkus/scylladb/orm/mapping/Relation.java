package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Relation {
    String fkColumn();
}
