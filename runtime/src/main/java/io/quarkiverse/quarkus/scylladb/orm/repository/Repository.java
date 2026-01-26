package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    protected final CqlSession session;
    protected final String tableName;
    protected final EntityMapper<T> mapper;
    protected final RepositoryRegistry registry;

    private final Map<String, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();

    public Repository() {
        this.session = null;
        this.tableName = null;
        this.mapper = null;
        this.registry = null;
    }

    public Repository(CqlSession session, String tableName, EntityMapper<T> mapper, RepositoryRegistry registry) {
        this.session = session;
        this.tableName = tableName;
        this.mapper = mapper;
        this.registry = registry;
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
                .collect(Collectors.joining(" AND "));
    }

    private Object[] buildKeyParams(EntityMapper<T> mapper, T entity) {
        List<KeyComponent<?>> pk = mapper.getPartitionKeyComponents(entity);
        List<KeyComponent<?>> ck = mapper.getClusteringKeyComponents(entity);

        Object[] params = new Object[pk.size() + ck.size()];
        int i = 0;
        for (KeyComponent<?> kc : pk)
            params[i++] = kc.value();
        for (KeyComponent<?> kc : ck)
            params[i++] = kc.value();
        return params;
    }

    private Object[] concat(Object[] left, Object[] right) {
        Object[] out = new Object[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    // ----------------------------------------------------------
    // Core Repository Methods
    // ----------------------------------------------------------

    /**
     * Backward compatible single-ID fetch (single partition key only).
     * For composite keys, use {@link #findByKeys(Object...)}.
     */
    public T findById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException("findById requires exactly one partition key column.");
        }
        String cql = String.format("SELECT * FROM %s WHERE %s = ?", tableName, pkNames[0]);
        ResultSet rs = doExecuteQuery(cql, id);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    /**
     * Generic fetch using all key parts (partition + clustering) in ordinal order.
     */
    public T findByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);

        int expected = pkNames.length + ckNames.length;
        if (keys.length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " key parts, got " + keys.length);
        }

        String cql = String.format("SELECT * FROM %s WHERE %s", tableName, where);
        ResultSet rs = doExecuteQuery(cql, keys);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    public List<T> findAll() {
        String cql = "SELECT * FROM " + tableName;
        ResultSet rs = doExecuteQuery(cql);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public List<T> findAll(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d", tableName, sortClause, pageable.size());

        SimpleStatement stmt = SimpleStatement.newInstance(cql)
                .setPageSize(pageable.size());

        if (pageable.pagingState() != null) {
            stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
        }

        ResultSet rs = session.execute(stmt);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public Paged<T> findAllPaged(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d", tableName, sortClause, pageable.size());

        SimpleStatement stmt = SimpleStatement.newInstance(cql)
                .setPageSize(pageable.size());

        if (pageable.pagingState() != null) {
            stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
        }

        ResultSet rs = session.execute(stmt);

        List<T> content = StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();

        String nextState = rs.getExecutionInfo().getSafePagingState() != null
                ? rs.getExecutionInfo().getSafePagingState().toString()
                : null;

        return new Paged<>(content, -1, nextState);
    }

    public long count() {
        String cql = "SELECT COUNT(*) as count FROM " + tableName;
        ResultSet rs = doExecuteQuery(cql);
        Row row = rs.one();
        return row != null ? row.getLong("count") : 0L;
    }

    /**
     * Backward compatible exists via single partition key.
     * For composite keys, use {@link #exists(T)}.
     */
    public boolean existsById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException("existsById requires exactly one partition key column.");
        }
        String cql = String.format("SELECT COUNT(1) as cnt FROM %s WHERE %s = ?",
                tableName, pkNames[0]);
        ResultSet rs = doExecuteQuery(cql, id);
        Row row = rs.one();
        return row != null && row.getLong("cnt") > 0;
    }

    public boolean exists(T entity) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("SELECT COUNT(1) AS cnt FROM %s WHERE %s", tableName, where);
        ResultSet rs = doExecuteQuery(cql, buildKeyParams(mapper, entity));
        Row row = rs.one();
        return row != null && row.getLong("cnt") > 0;
    }

    public T save(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);

        String columns = String.join(", ", properties.keySet());
        String placeholders = properties.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        String cql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);

        doExecuteWrite(cql, properties.values().toArray());

        // Reload by full key
        Object[] keys = buildKeyParams(mapper, entity);
        return findByKeys(keys);
    }

    public T merge(T entity) {
        // INSERT is an upsert in Cassandra
        return save(entity);
    }

    public T update(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();

        // remove key columns from SET clause
        for (String k : pkNames)
            properties.remove(k);
        for (String k : ckNames)
            properties.remove(k);

        if (properties.isEmpty()) {
            return findByKeys(buildKeyParams(mapper, entity));
        }

        String setClause = properties.keySet().stream()
                .map(k -> k + " = ?")
                .collect(Collectors.joining(", "));

        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("UPDATE %s SET %s WHERE %s", tableName, setClause, where);

        Object[] values = properties.values().toArray();
        Object[] keys = buildKeyParams(mapper, entity);
        Object[] params = concat(values, keys);

        doExecuteWrite(cql, params);

        return findByKeys(keys);
    }

    /**
     * Backward compatible delete via single partition key.
     * For composite keys, use {@link #delete(T)} or {@link #deleteByKeys(Object...)}.
     */
    public void deleteById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException("deleteById requires exactly one partition key column.");
        }
        String cql = String.format("DELETE FROM %s WHERE %s = ?", tableName, pkNames[0]);
        doExecuteWrite(cql, id);
    }

    public void delete(T entity) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);
        doExecuteWrite(cql, buildKeyParams(mapper, entity));
    }

    public void deleteByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);

        int expected = pkNames.length + ckNames.length;
        if (keys.length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " key parts, got " + keys.length);
        }

        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);
        doExecuteWrite(cql, keys);
    }

    // ----------------------------------------------------------
    // Query Methods
    // ----------------------------------------------------------

    public List<T> query(String cql, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public List<T> query(String cql, Pageable pageable, Sortable sortable, Object... params) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = String.format("%s %s LIMIT %d", cql, sortClause, pageable.size());

        SimpleStatement stmt = SimpleStatement.newInstance(pagedCql, params)
                .setPageSize(pageable.size());

        if (pageable.pagingState() != null) {
            stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
        }

        ResultSet rs = session.execute(stmt);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public Paged<T> queryPaged(String baseCql, Map<String, Object> params, Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = baseCql + " " + sortClause + " LIMIT " + pageable.size();

        SimpleStatement stmt = SimpleStatement.builder(pagedCql)
                .addPositionalValues(params.values())
                .setPageSize(pageable.size())
                .build();

        if (pageable.pagingState() != null) {
            stmt = stmt.setPagingState(PagingState.fromString(pageable.pagingState()));
        }

        ResultSet rs = session.execute(stmt);

        List<T> content = StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();

        String nextState = rs.getExecutionInfo().getSafePagingState() != null
                ? rs.getExecutionInfo().getSafePagingState().toString()
                : null;

        return new Paged<>(content, -1, nextState);
    }

    public T querySingle(String cql, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    public <R> R queryScalar(String cql, Function<Row, R> mapperFn, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? mapperFn.apply(row) : null;
    }

    public void execute(String cql, Object... params) {
        doExecuteWrite(cql, params);
    }

    // ----------------------------------------------------------
    // Internal
    // ----------------------------------------------------------

    private ResultSet doExecuteQuery(String cql, Object... params) {
        try {
            PreparedStatement ps = preparedStatements.computeIfAbsent(cql, session::prepare);
            BoundStatement bound = ps.bind(params);
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    private ResultSet doExecuteWrite(String cql, Object... params) {
        try {
            PreparedStatement ps = preparedStatements.computeIfAbsent(cql, session::prepare);
            BoundStatement bound = ps.bind(params);
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, params, e);
        }
    }

    private T mapRow(Row row) {
        try {
            return mapper.map(row);
        } catch (Exception e) {
            throw new ScyllaMappingException(tableName, "Failed to map row to entity", e);
        }
    }

    public EntityMapper<T> getEntityMapper() {
        return mapper;
    }
}
