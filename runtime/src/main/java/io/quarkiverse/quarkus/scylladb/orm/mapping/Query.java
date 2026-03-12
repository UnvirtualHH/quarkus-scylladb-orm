package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.quarkiverse.quarkus.scylladb.orm.enums.ReturnType;

@Retention(RetentionPolicy.CLASS) //
@Target(ElementType.TYPE)
public @interface Query {
    String name();

    String cql();

    ReturnType returnType() default ReturnType.SINGLE;

    @interface Param {
        String name();

        Class<?> type();
    }

    Class<?> resultClass() default void.class;

    Param[] paramTypes() default {};
}
