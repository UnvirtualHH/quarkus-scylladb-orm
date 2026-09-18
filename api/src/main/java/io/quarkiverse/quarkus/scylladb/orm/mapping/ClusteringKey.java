package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClusteringKey {
    /**
     * Order for composite clustering keys (0 = first, 1 = second, ...).
     */
    int ordinal() default 0;

    // An `order()` attribute used to be declared here and was read by nothing: clustering
    // order is a property of the table (CREATE TABLE ... WITH CLUSTERING ORDER BY), and
    // this extension does not generate DDL. It was silently ignored, so it is gone.
    // Sort a query with Sortable, or with an ORDER BY in a @Query.
}
