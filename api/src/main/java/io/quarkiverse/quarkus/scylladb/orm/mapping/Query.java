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

    /**
     * Whether this query is allowed to contain schema-altering (CREATE/ALTER/DROP) or
     * TRUNCATE statements. Defaults to {@code false} so that application roles can run
     * with least privilege: a production app role should not hold schema/truncate
     * permissions. Set to {@code true} only for deliberate migration/maintenance queries
     * (and ensure the DB role actually has the required grants).
     */
    boolean allowSchemaChanges() default false;

    @interface Param {
        String name();

        Class<?> type();
    }

    Class<?> resultClass() default void.class;

    Param[] paramTypes() default {};
}
