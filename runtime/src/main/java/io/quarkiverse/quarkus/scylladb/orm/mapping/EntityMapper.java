package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.util.List;
import java.util.Map;

import com.datastax.oss.driver.api.core.cql.Row;

/**
 * Core interface for mapping between ScyllaDB rows and entities.
 * Analog to Neo4j's EntityMapper but for CQL instead of Cypher.
 */
public interface EntityMapper<T> {

    /**
     * Maps a ScyllaDB Row to the entity.
     *
     * @param row The ScyllaDB row retrieved from the database.
     * @return The mapped entity of type T.
     */
    T map(Row row);

    /**
     * Converts the entity into a map of properties for CQL queries.
     * In ScyllaDB we don't have relationships, so simpler than Neo4j.
     *
     * @param entity The entity to convert.
     * @return A map of column names to values for INSERT/UPDATE.
     */
    Map<String, Object> toProperties(T entity);

    /**
     * Returns the entity class.
     *
     * @return The entity class.
     */
    Class<T> getEntityType();

    String[] getPartitionKeyNames();

    String[] getClusteringKeyNames();

    List<KeyComponent<?>> getPartitionKeyComponents(T entity);

    List<KeyComponent<?>> getClusteringKeyComponents(T entity);
}
