package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a field to a differently named column.
 * <p>
 * On an entity field it names the column the mapper reads and writes. On a field of a
 * projection DTO, or a component of a projection record ({@code @Query(resultClass = ...)}),
 * it names the column that field is read from — so a {@code full_name} column can land in
 * a {@code fullName} component without aliasing it in the CQL.
 */
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {
    /**
     * Column name (default: field name)
     */
    String value() default "";
}