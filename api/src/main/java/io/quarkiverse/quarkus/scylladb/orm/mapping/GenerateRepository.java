package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GenerateRepository {
    RepositoryType value() default RepositoryType.BOTH;

    enum RepositoryType {
        BLOCKING,
        REACTIVE,
        BOTH;
    }
}