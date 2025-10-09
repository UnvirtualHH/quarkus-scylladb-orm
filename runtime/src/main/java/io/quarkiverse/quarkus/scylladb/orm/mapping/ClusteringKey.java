package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClusteringKey {
    /**
     * Order for composite clustering keys
     */
    int ordinal() default 0;

    /**
     * Clustering order (ASC or DESC)
     */
    ClusteringOrder order() default ClusteringOrder.ASC;
}