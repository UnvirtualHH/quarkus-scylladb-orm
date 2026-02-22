package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.*;
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

        ResultSet rs = doExecutePagedQuery(cql, pageable);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public Paged<T> findAllPaged(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d", tableName, sortClause, pageable.size());

        ResultSet rs = doExecutePagedQuery(cql, pageable);

        List<T> content = StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();

        String nextState = null;
        if (!rs.isFullyFetched()) {
            var pagingState = rs.getExecutionInfo().getSafePagingState();
            if (pagingState != null) {
                nextState = pagingState.toString();
            }
        }

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
     * Uses LIMIT 1 instead of COUNT for optimal ScyllaDB performance.
     * For composite keys, use {@link #exists(T)}.
     */
    public boolean existsById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            throw new IllegalStateException("existsById requires exactly one partition key column.");
        }
        String cql = String.format("SELECT %s FROM %s WHERE %s = ? LIMIT 1",
                pkNames[0], tableName, pkNames[0]);
        ResultSet rs = doExecuteQuery(cql, id);
        return rs.one() != null;
    }

    /**
     * Check existence by full primary key (partition + clustering).
     * Uses LIMIT 1 instead of COUNT for optimal ScyllaDB performance.
     */
    public boolean exists(T entity) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("SELECT %s FROM %s WHERE %s LIMIT 1",
                pkNames[0], tableName, where);
        ResultSet rs = doExecuteQuery(cql, buildKeyParams(mapper, entity));
        return rs.one() != null;
    }

    public T save(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);

        String columns = String.join(", ", properties.keySet());
        String placeholders = properties.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        String cql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);

        doExecuteWrite(cql, properties.values().toArray());

        return entity;
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
            return entity;
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

        return entity;
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

    public List<T> query(String cql, Object p1) {
        ResultSet rs = doExecuteQuery(cql, p1);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public List<T> query(String cql, Object p1, Object p2) {
        ResultSet rs = doExecuteQuery(cql, p1, p2);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public List<T> query(String cql, Object p1, Object p2, Object p3) {
        ResultSet rs = doExecuteQuery(cql, p1, p2, p3);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public List<T> query(String cql, Pageable pageable, Sortable sortable, Object... params) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = String.format("%s %s LIMIT %d", cql, sortClause, pageable.size());

        ResultSet rs = doExecutePagedQuery(pagedCql, pageable, params);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public Paged<T> queryPaged(String baseCql, Map<String, Object> params, Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = baseCql + " " + sortClause + " LIMIT " + pageable.size();

        ResultSet rs = doExecutePagedQuery(pagedCql, pageable, params);

        List<T> content = StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();

        String nextState = null;
        if (!rs.isFullyFetched()) {
            var pagingState = rs.getExecutionInfo().getSafePagingState();
            if (pagingState != null) {
                nextState = pagingState.toString();
            }
        }

        return new Paged<>(content, -1, nextState);
    }

    public T querySingle(String cql, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    public T querySingle(String cql, Object p1) {
        ResultSet rs = doExecuteQuery(cql, p1);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    public T querySingle(String cql, Object p1, Object p2) {
        ResultSet rs = doExecuteQuery(cql, p1, p2);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    public T querySingle(String cql, Object p1, Object p2, Object p3) {
        ResultSet rs = doExecuteQuery(cql, p1, p2, p3);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    public <R> R queryScalar(String cql, Function<Row, R> mapperFn, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? mapperFn.apply(row) : null;
    }

    /**
     * Execute a scalar query and return the first column of the first row as Long.
     * Used by generated @Query methods with ReturnType.SCALAR.
     */
    public Long queryScalar(String cql, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? row.getLong(0) : null;
    }

    public Long queryScalar(String cql, Object p1) {
        ResultSet rs = doExecuteQuery(cql, p1);
        Row row = rs.one();
        return row != null ? row.getLong(0) : null;
    }

    public Long queryScalar(String cql, Object p1, Object p2) {
        ResultSet rs = doExecuteQuery(cql, p1, p2);
        Row row = rs.one();
        return row != null ? row.getLong(0) : null;
    }

    public Long queryScalar(String cql, Object p1, Object p2, Object p3) {
        ResultSet rs = doExecuteQuery(cql, p1, p2, p3);
        Row row = rs.one();
        return row != null ? row.getLong(0) : null;
    }

    public void execute(String cql, Object... params) {
        doExecuteWrite(cql, params);
    }

    public void execute(String cql, Object p1) {
        doExecuteWrite(cql, p1);
    }

    public void execute(String cql, Object p1, Object p2) {
        doExecuteWrite(cql, p1, p2);
    }

    public void execute(String cql, Object p1, Object p2, Object p3) {
        doExecuteWrite(cql, p1, p2, p3);
    }

    // ----------------------------------------------------------
    // Internal
    // ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private BoundStatement bindParams(PreparedStatement ps, Object[] params) {
        if (params.length == 1 && params[0] instanceof Map<?, ?> map) {
            BoundStatementBuilder builder = ps.boundStatementBuilder();
            map.forEach((k, v) -> {
                if (v != null) {
                    builder.set(k.toString(), v, (Class<Object>) v.getClass());
                } else {
                    builder.setToNull(k.toString());
                }
            });
            return builder.build();
        }
        return ps.bind(params);
    }

    @SuppressWarnings("unchecked")
    private BoundStatement bind1(PreparedStatement ps, Object p1) {
        BoundStatementBuilder builder = ps.boundStatementBuilder();
        if (p1 != null) {
            builder.set(0, p1, (Class<Object>) p1.getClass());
        } else {
            builder.setToNull(0);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private BoundStatement bind2(PreparedStatement ps, Object p1, Object p2) {
        BoundStatementBuilder builder = ps.boundStatementBuilder();
        if (p1 != null) {
            builder.set(0, p1, (Class<Object>) p1.getClass());
        } else {
            builder.setToNull(0);
        }
        if (p2 != null) {
            builder.set(1, p2, (Class<Object>) p2.getClass());
        } else {
            builder.setToNull(1);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private BoundStatement bind3(PreparedStatement ps, Object p1, Object p2, Object p3) {
        BoundStatementBuilder builder = ps.boundStatementBuilder();
        if (p1 != null) {
            builder.set(0, p1, (Class<Object>) p1.getClass());
        } else {
            builder.setToNull(0);
        }
        if (p2 != null) {
            builder.set(1, p2, (Class<Object>) p2.getClass());
        } else {
            builder.setToNull(1);
        }
        if (p3 != null) {
            builder.set(2, p3, (Class<Object>) p3.getClass());
        } else {
            builder.setToNull(2);
        }
        return builder.build();
    }

    private ResultSet doExecuteQuery(String cql, Object... params) {
        try {
            PreparedStatement ps = session.prepare(cql);
            BoundStatement bound = bindParams(ps, params);
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    private ResultSet doExecuteQuery(String cql, Object p1) {
        try {
            PreparedStatement ps = session.prepare(cql);
            return session.execute(bind1(ps, p1));
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[] { p1 }, e);
        }
    }

    private ResultSet doExecuteQuery(String cql, Object p1, Object p2) {
        try {
            PreparedStatement ps = session.prepare(cql);
            return session.execute(bind2(ps, p1, p2));
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[] { p1, p2 }, e);
        }
    }

    private ResultSet doExecuteQuery(String cql, Object p1, Object p2, Object p3) {
        try {
            PreparedStatement ps = session.prepare(cql);
            return session.execute(bind3(ps, p1, p2, p3));
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, new Object[] { p1, p2, p3 }, e);
        }
    }

    private ResultSet doExecutePagedQuery(String cql, Pageable pageable, Object... params) {
        try {
            PreparedStatement ps = session.prepare(cql);
            BoundStatement bound = bindParams(ps, params);
            bound = bound.setPageSize(pageable.size());
            if (pageable.pagingState() != null) {
                bound = bound.setPagingState(PagingState.fromString(pageable.pagingState()));
            }
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    private ResultSet doExecuteWrite(String cql, Object... params) {
        try {
            PreparedStatement ps = session.prepare(cql);
            BoundStatement bound = bindParams(ps, params);
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, params, e);
        }
    }

    private ResultSet doExecuteWrite(String cql, Object p1) {
        try {
            PreparedStatement ps = session.prepare(cql);
            return session.execute(bind1(ps, p1));
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, new Object[] { p1 }, e);
        }
    }

    private ResultSet doExecuteWrite(String cql, Object p1, Object p2) {
        try {
            PreparedStatement ps = session.prepare(cql);
            return session.execute(bind2(ps, p1, p2));
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, new Object[] { p1, p2 }, e);
        }
    }

    private ResultSet doExecuteWrite(String cql, Object p1, Object p2, Object p3) {
        try {
            PreparedStatement ps = session.prepare(cql);
            return session.execute(bind3(ps, p1, p2, p3));
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, new Object[] { p1, p2, p3 }, e);
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
