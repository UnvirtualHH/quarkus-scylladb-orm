package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;

import io.quarkiverse.quarkus.scylladb.orm.exception.ScyllaMappingException;
import io.quarkiverse.quarkus.scylladb.orm.exception.ScyllaQueryException;
import io.quarkiverse.quarkus.scylladb.orm.exception.ScyllaWriteException;
import io.quarkiverse.quarkus.scylladb.orm.mapping.EntityMapper;
import io.quarkiverse.quarkus.scylladb.orm.mapping.KeyComponent;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Pageable;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Paged;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Sortable;

/**
 * Non-reactive repository base for Scylla/Cassandra.
 * Supports composite primary keys (partition + clustering).
 */
public abstract class Repository<T, ID> {

    private static final int UNKNOWN_TOTAL_COUNT = -1;
    private static final int MAX_PREPARED_STATEMENTS = 1000;

    protected final CqlSession session;
    protected final String tableName;
    protected final EntityMapper<T> mapper;
    protected final RepositoryRegistry registry;

    private final Map<String, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();

    /**
     * Default constructor for CDI/dependency injection frameworks.
     * Fields will be null - this instance should not be used directly.
     */
    public Repository() {
        this.session = null;
        this.tableName = null;
        this.mapper = null;
        this.registry = null;
    }

    public Repository(CqlSession session, String tableName, EntityMapper<T> mapper, RepositoryRegistry registry) {
        this.session = Objects.requireNonNull(session, "CqlSession must not be null");
        this.tableName = Objects.requireNonNull(tableName, "Table name must not be null");
        this.mapper = Objects.requireNonNull(mapper, "EntityMapper must not be null");
        this.registry = registry;

        if (tableName.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank");
        }
    }

    protected abstract Class<T> getEntityType();

    // ----------------------------------------------------------
    // Helper (keys + where clause)
    // ----------------------------------------------------------

    private String buildWhereClause(String[] pkNames, String[] ckNames) {
        String[] names = java.util.stream.Stream.concat(
                Arrays.stream(pkNames),
                Arrays.stream(ckNames)).toArray(String[]::new);

        if (names.length == 0) {
            throw new IllegalStateException("No primary key columns defined for table " + tableName);
        }

        return Arrays.stream(names)
                .map(n -> n + " = ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
    }

    private Object[] buildKeyParams(T entity) {
        Objects.requireNonNull(entity, "Entity must not be null");

        List<KeyComponent<?>> pk = mapper.getPartitionKeyComponents(entity);
        List<KeyComponent<?>> ck = mapper.getClusteringKeyComponents(entity);

        Object[] params = new Object[pk.size() + ck.size()];
        int i = 0;
        for (KeyComponent<?> kc : pk) {
            params[i++] = kc.value();
        }
        for (KeyComponent<?> kc : ck) {
            params[i++] = kc.value();
        }
        return params;
    }

    private void validateKeyCount(Object[] keys, int expected) {
        if (keys == null) {
            throw new IllegalArgumentException("Keys must not be null");
        }
        if (keys.length != expected) {
            throw new IllegalArgumentException(
                    "Expected " + expected + " key parts, got " + keys.length);
        }
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) {
                throw new IllegalArgumentException("Key at index " + i + " must not be null");
            }
        }
    }

    private Object[] concatArrays(Object[] left, Object[] right) {
        Object[] result = new Object[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    // ----------------------------------------------------------
    // Core Repository Methods
    // ----------------------------------------------------------

    /**
     * Backward compatible single-ID fetch (single partition key only).
     * For composite keys, use {@link #findByKeys(Object...)}.
     */
    public T findById(ID id) {
        if (id == null) {
            throw new IllegalArgumentException("ID must not be null");
        }

        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException(
                    "findById requires exactly one partition key, found " + pkNames.length);
        }

        String cql = String.format("SELECT * FROM %s WHERE %s = ?", tableName, pkNames[0]);
        try {
            ResultSet rs = doExecute(cql, id);
            Row row = rs.one();
            return row != null ? mapper.map(row) : null;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[] { id }, e);
        }
    }

    /**
     * Generic fetch using all key parts (partition + clustering) in ordinal order.
     */
    public T findByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();

        int expected = pkNames.length + ckNames.length;
        validateKeyCount(keys, expected);

        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("SELECT * FROM %s WHERE %s", tableName, where);

        try {
            ResultSet rs = doExecute(cql, keys);
            Row row = rs.one();
            return row != null ? mapper.map(row) : null;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, keys, e);
        }
    }

    public List<T> findAll() {
        String cql = "SELECT * FROM " + tableName;
        try {
            ResultSet rs = doExecute(cql);
            return StreamSupport.stream(rs.spliterator(), false)
                    .map(row -> {
                        try {
                            return mapper.map(row);
                        } catch (Exception e) {
                            throw new ScyllaMappingException(tableName, "Failed to map row", e);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[0], e);
        }
    }

    public List<T> findAll(Pageable pageable, Sortable sortable) {
        Objects.requireNonNull(pageable, "Pageable must not be null");

        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d", tableName, sortClause, pageable.size());

        try {
            SimpleStatement stmt = SimpleStatement.newInstance(cql)
                    .setPageSize(pageable.size());

            if (pageable.pagingState() != null) {
                stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
            }

            ResultSet rs = session.execute(stmt);
            return StreamSupport.stream(rs.spliterator(), false)
                    .map(row -> {
                        try {
                            return mapper.map(row);
                        } catch (Exception e) {
                            throw new ScyllaMappingException(tableName, "Failed to map row", e);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[0], e);
        }
    }

    public Paged<T> findAllPaged(Pageable pageable, Sortable sortable) {
        Objects.requireNonNull(pageable, "Pageable must not be null");

        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d", tableName, sortClause, pageable.size());

        try {
            SimpleStatement stmt = SimpleStatement.newInstance(cql)
                    .setPageSize(pageable.size());

            if (pageable.pagingState() != null) {
                stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
            }

            ResultSet rs = session.execute(stmt);

            List<T> content = StreamSupport.stream(rs.spliterator(), false)
                    .map(row -> {
                        try {
                            return mapper.map(row);
                        } catch (Exception e) {
                            throw new ScyllaMappingException(tableName, "Failed to map row", e);
                        }
                    })
                    .toList();

            String nextState = rs.getExecutionInfo().getSafePagingState() != null
                    ? rs.getExecutionInfo().getSafePagingState().toString()
                    : null;

            return new Paged<>(content, UNKNOWN_TOTAL_COUNT, nextState);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[0], e);
        }
    }

    public long count() {
        String cql = "SELECT COUNT(*) as count FROM " + tableName;
        try {
            ResultSet rs = doExecute(cql);
            Row row = rs.one();
            return row != null ? row.getLong("count") : 0L;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[0], e);
        }
    }

    /**
     * Backward compatible exists via single partition key.
     * For composite keys, use {@link #exists(T)}.
     */
    public boolean existsById(ID id) {
        if (id == null) {
            throw new IllegalArgumentException("ID must not be null");
        }

        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException(
                    "existsById requires exactly one partition key, found " + pkNames.length);
        }

        String cql = String.format("SELECT COUNT(1) as cnt FROM %s WHERE %s = ?",
                tableName, pkNames[0]);

        try {
            ResultSet rs = doExecute(cql, id);
            Row row = rs.one();
            return row != null && row.getLong("cnt") > 0;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[] { id }, e);
        }
    }

    public boolean exists(T entity) {
        Objects.requireNonNull(entity, "Entity must not be null");

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("SELECT COUNT(1) AS cnt FROM %s WHERE %s", tableName, where);

        Object[] keys = buildKeyParams(entity);
        try {
            ResultSet rs = doExecute(cql, keys);
            Row row = rs.one();
            return row != null && row.getLong("cnt") > 0;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, keys, e);
        }
    }

    public T save(T entity) {
        Objects.requireNonNull(entity, "Entity must not be null");

        Map<String, Object> properties = mapper.toProperties(entity);

        String columns = String.join(", ", properties.keySet());
        String placeholders = properties.keySet().stream()
                .map(k -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        String cql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);

        Object[] params = properties.values().toArray();
        try {
            doExecute(cql, params);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, params, e);
        }

        // Reload by full key
        Object[] keys = buildKeyParams(entity);
        return findByKeys(keys);
    }

    public T merge(T entity) {
        // INSERT is an upsert in Cassandra
        return save(entity);
    }

    public T update(T entity) {
        Objects.requireNonNull(entity, "Entity must not be null");

        Map<String, Object> properties = mapper.toProperties(entity);

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();

        // Remove key columns from SET clause
        for (String k : pkNames) {
            properties.remove(k);
        }
        for (String k : ckNames) {
            properties.remove(k);
        }

        Object[] keys = buildKeyParams(entity);

        if (properties.isEmpty()) {
            return findByKeys(keys);
        }

        String setClause = properties.keySet().stream()
                .map(k -> k + " = ?")
                .collect(java.util.stream.Collectors.joining(", "));

        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("UPDATE %s SET %s WHERE %s", tableName, setClause, where);

        Object[] values = properties.values().toArray();
        Object[] params = concatArrays(values, keys);

        try {
            doExecute(cql, params);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, params, e);
        }

        return findByKeys(keys);
    }

    /**
     * Backward compatible delete via single partition key.
     * For composite keys, use {@link #delete(T)} or {@link #deleteByKeys(Object...)}.
     */
    public void deleteById(ID id) {
        if (id == null) {
            throw new IllegalArgumentException("ID must not be null");
        }

        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException(
                    "deleteById requires exactly one partition key, found " + pkNames.length);
        }

        String cql = String.format("DELETE FROM %s WHERE %s = ?", tableName, pkNames[0]);
        try {
            doExecute(cql, id);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, new Object[] { id }, e);
        }
    }

    public void delete(T entity) {
        Objects.requireNonNull(entity, "Entity must not be null");

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);

        Object[] keys = buildKeyParams(entity);
        try {
            doExecute(cql, keys);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, keys, e);
        }
    }

    public void deleteByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();

        int expected = pkNames.length + ckNames.length;
        validateKeyCount(keys, expected);

        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);

        try {
            doExecute(cql, keys);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, keys, e);
        }
    }

    // ----------------------------------------------------------
    // Query Methods
    // ----------------------------------------------------------

    public List<T> query(String cql, Object... params) {
        Objects.requireNonNull(cql, "CQL must not be null");

        try {
            ResultSet rs = doExecute(cql, params);
            return StreamSupport.stream(rs.spliterator(), false)
                    .map(row -> {
                        try {
                            return mapper.map(row);
                        } catch (Exception e) {
                            throw new ScyllaMappingException(tableName, "Failed to map row", e);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    public List<T> query(String cql, Pageable pageable, Sortable sortable, Object... params) {
        Objects.requireNonNull(cql, "CQL must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");

        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = String.format("%s %s LIMIT %d", cql, sortClause, pageable.size());

        try {
            SimpleStatement stmt = SimpleStatement.newInstance(pagedCql, params)
                    .setPageSize(pageable.size());

            if (pageable.pagingState() != null) {
                stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
            }

            ResultSet rs = session.execute(stmt);
            return StreamSupport.stream(rs.spliterator(), false)
                    .map(row -> {
                        try {
                            return mapper.map(row);
                        } catch (Exception e) {
                            throw new ScyllaMappingException(tableName, "Failed to map row", e);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, pagedCql, params, e);
        }
    }

    public Paged<T> queryPaged(String baseCql, Map<String, Object> params, Pageable pageable, Sortable sortable) {
        Objects.requireNonNull(baseCql, "CQL must not be null");
        Objects.requireNonNull(params, "Parameters must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");

        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = baseCql + " " + sortClause + " LIMIT " + pageable.size();

        try {
            SimpleStatement stmt = SimpleStatement.builder(pagedCql)
                    .addPositionalValues(params.values())
                    .setPageSize(pageable.size())
                    .build();

            if (pageable.pagingState() != null) {
                stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
            }

            ResultSet rs = session.execute(stmt);

            List<T> content = StreamSupport.stream(rs.spliterator(), false)
                    .map(row -> {
                        try {
                            return mapper.map(row);
                        } catch (Exception e) {
                            throw new ScyllaMappingException(tableName, "Failed to map row", e);
                        }
                    })
                    .toList();

            String nextState = rs.getExecutionInfo().getSafePagingState() != null
                    ? rs.getExecutionInfo().getSafePagingState().toString()
                    : null;

            return new Paged<>(content, UNKNOWN_TOTAL_COUNT, nextState);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, pagedCql, params.values().toArray(), e);
        }
    }

    public T querySingle(String cql, Object... params) {
        Objects.requireNonNull(cql, "CQL must not be null");

        try {
            ResultSet rs = doExecute(cql, params);
            Row row = rs.one();
            return row != null ? mapper.map(row) : null;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    public <R> R queryScalar(String cql, Function<Row, R> mapperFn, Object... params) {
        Objects.requireNonNull(cql, "CQL must not be null");
        Objects.requireNonNull(mapperFn, "Mapper function must not be null");

        try {
            ResultSet rs = doExecute(cql, params);
            Row row = rs.one();
            return row != null ? mapperFn.apply(row) : null;
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    public void execute(String cql, Object... params) {
        Objects.requireNonNull(cql, "CQL must not be null");

        try {
            doExecute(cql, params);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, params, e);
        }
    }

    // ----------------------------------------------------------
    // Internal
    // ----------------------------------------------------------

    private ResultSet doExecute(String cql, Object... params) {
        PreparedStatement ps = getPreparedStatement(cql);
        BoundStatement bound = ps.bind(params);
        return session.execute(bound);
    }

    private PreparedStatement getPreparedStatement(String cql) {
        // Check cache first
        PreparedStatement cached = preparedStatements.get(cql);
        if (cached != null) {
            return cached;
        }

        // Prepare and cache with eviction
        synchronized (preparedStatements) {
            // Double-check in case another thread prepared it
            cached = preparedStatements.get(cql);
            if (cached != null) {
                return cached;
            }

            // Evict oldest entry if cache is full
            if (preparedStatements.size() >= MAX_PREPARED_STATEMENTS) {
                Iterator<String> it = preparedStatements.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }

            PreparedStatement ps = session.prepare(cql);
            preparedStatements.put(cql, ps);
            return ps;
        }
    }

    public EntityMapper<T> getEntityMapper() {
        return mapper;
    }

    /**
     * Clears the prepared statement cache. Useful for testing or memory management.
     */
    public void clearPreparedStatementCache() {
        preparedStatements.clear();
    }

    /**
     * Returns the number of cached prepared statements.
     */
    public int getPreparedStatementCacheSize() {
        return preparedStatements.size();
    }
}