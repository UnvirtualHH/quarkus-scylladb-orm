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

        /**
         * How this parameter reaches the statement. Defaults to {@link Binding#AUTO}, so
         * declaring a type never changes existing behaviour.
         */
        Binding binding() default Binding.AUTO;
    }

    /**
     * Whether a {@code :name} marker is bound as a value or interpolated into the
     * statement text.
     * <p>
     * Interpolation exists because CQL has places a bind marker cannot go — {@code ORDER
     * BY} takes a column name, not a value. Which parameters those are was decided purely
     * by name ({@code limit}, {@code order}, {@code orderby}, {@code sort}), which is a
     * good guess and a bad rule: an entity with a column actually called {@code sort}
     * could not query it, because {@code :sort} was interpolated and then rejected by the
     * "column ASC/DESC" format check. This makes the guess overridable.
     */
    enum Binding {
        /** Decide by name, as before. */
        AUTO,
        /** Interpolate into the CQL text, after the format check for its kind. */
        STRUCTURAL,
        /** Bind as an ordinary value, whatever the parameter is called. */
        BOUND
    }

    Class<?> resultClass() default void.class;

    Param[] paramTypes() default {};
}
