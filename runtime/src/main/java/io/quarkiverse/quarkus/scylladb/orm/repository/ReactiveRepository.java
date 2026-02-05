package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Reactive base repository for Scylla DB using Mutiny.
 */
public abstract class ReactiveRepository<T, ID> {

    protected final CqlSession session;
    protected final String tableName;
    protected final EntityMapper<T> mapper;
    protected final ReactiveRepositoryRegistry registry;

    private final Map<String, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();

    public ReactiveRepository() {
        this.session = null;
        this.tableName = null;
        this.mapper = null;
        this.registry = null;
    }

    public ReactiveRepository(
            CqlSession session,
            String tableName,
            EntityMapper<T> mapper,
            ReactiveRepositoryRegistry registry) {
        this.session = session;
        this.tableName = tableName;
        this.mapper = mapper;
        this.registry = registry;
    }

    protected abstract Class<T> getEntityType();

    // ----------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------

    private Object[] buildKeyParams(T entity) {
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

    // ----------------------------------------------------------
    // Repository API
    // ----------------------------------------------------------

    public Uni<T> findById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            return Uni.createFrom().failure(new IllegalStateException("findById requires exactly one partition key."));
        }
        String cql = String.format("SELECT * FROM %s WHERE %s = ?", tableName, pkNames[0]);
        return executeQueryForOne(cql, id);
    }

    public Multi<T> findAll() {
        String cql = "SELECT * FROM " + tableName;
        return executeQueryForList(cql);
    }

    public Multi<T> findAll(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d",
                tableName, sortClause, pageable.size());

        return Uni.createFrom().completionStage(() -> prepareAndExecutePaged(cql, pageable))
                .onItem().transformToMulti(
                        rs -> Multi.createFrom().iterable(() -> rs.currentPage().iterator()))
                .map(mapper::map);
    }

    public Uni<Paged<T>> findAllPaged(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT * FROM %s %s LIMIT %d",
                tableName, sortClause, pageable.size());

        return Uni.createFrom().completionStage(() -> prepareAndExecutePaged(cql, pageable))
                .map(rs -> {
                    List<T> content = StreamSupport.stream(rs.currentPage().spliterator(), false)
                            .map(mapper::map)
                            .toList();

                    String nextStateStr = null;
                    if (rs.hasMorePages()) {
                        var pagingState = rs.getExecutionInfo().getSafePagingState();
                        if (pagingState != null) {
                            nextStateStr = pagingState.toString();
                        }
                    }

                    return new Paged<>(content, -1, nextStateStr);
                });
    }

    public Uni<Long> count() {
        String cql = "SELECT COUNT(*) as count FROM " + tableName;
        return runScalarQuery(cql, row -> row.getLong("count"));
    }

    /**
     * Check existence by single partition key.
     * Uses LIMIT 1 instead of COUNT for optimal ScyllaDB performance.
     */
    public Uni<Boolean> existsById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            return Uni.createFrom().failure(new IllegalStateException("existsById requires exactly one partition key."));
        }
        String cql = String.format("SELECT %s FROM %s WHERE %s = ? LIMIT 1",
                pkNames[0], tableName, pkNames[0]);
        return runScalarQuery(cql, row -> Boolean.TRUE, id)
                .map(result -> result != null);
    }

    /**
     * Check existence by full primary key (partition + clustering).
     * Uses LIMIT 1 instead of COUNT for optimal ScyllaDB performance.
     */
    public Uni<Boolean> exists(T entity) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("SELECT %s FROM %s WHERE %s LIMIT 1",
                pkNames[0], tableName, where);
        return runScalarQuery(cql, row -> Boolean.TRUE, buildKeyParams(entity))
                .map(result -> result != null);
    }

    public Uni<T> save(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);

        String columns = String.join(", ", properties.keySet());
        String placeholders = properties.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        String cql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);

        return executeUpdate(cql, properties.values().toArray())
                .replaceWith(entity);
    }

    public Uni<T> update(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();

        // remove key columns from SET clause
        for (String k : pkNames)
            properties.remove(k);
        for (String k : ckNames)
            properties.remove(k);

        if (properties.isEmpty()) {
            return Uni.createFrom().item(entity);
        }

        String setClause = properties.keySet().stream()
                .map(k -> k + " = ?")
                .collect(Collectors.joining(", "));

        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("UPDATE %s SET %s WHERE %s", tableName, setClause, where);

        Object[] values = properties.values().toArray();
        Object[] keys = buildKeyParams(entity);
        Object[] params = concat(values, keys);

        return executeUpdate(cql, params).replaceWith(entity);
    }

    public Uni<T> merge(T entity) {
        return save(entity);
    }

    public Uni<Void> deleteById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            return Uni.createFrom().failure(new IllegalStateException("deleteById requires exactly one partition key."));
        }
        String cql = String.format("DELETE FROM %s WHERE %s = ?", tableName, pkNames[0]);
        return executeUpdate(cql, id);
    }

    public Uni<Void> delete(T entity) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);
        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);
        return executeUpdate(cql, buildKeyParams(entity));
    }

    public Uni<Void> deleteByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);

        int expected = pkNames.length + ckNames.length;
        if (keys.length != expected) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Expected " + expected + " key parts, got " + keys.length));
        }

        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);
        return executeUpdate(cql, keys);
    }

    public Uni<T> findByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = buildWhereClause(pkNames, ckNames);

        int expected = pkNames.length + ckNames.length;
        if (keys.length != expected) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "Expected " + expected + " key parts, got " + keys.length));
        }

        String cql = String.format("SELECT * FROM %s WHERE %s", tableName, where);
        return executeQueryForOne(cql, keys);
    }

    // ----------------------------------------------------------
    // Query Methods
    // ----------------------------------------------------------

    public Multi<T> query(String cql, Object... params) {
        return executeQueryForList(cql, params);
    }

    public Uni<T> querySingle(String cql, Object... params) {
        return executeQueryForOne(cql, params);
    }

    public Uni<Void> execute(String cql, Object... params) {
        return executeUpdate(cql, params);
    }

    public <R> Uni<R> queryScalar(String cql, Function<Row, R> mapperFn, Object... params) {
        return runScalarQuery(cql, mapperFn, params);
    }

    public Uni<Paged<T>> queryPaged(String baseCql, Map<String, Object> params, Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = baseCql + " " + sortClause + " LIMIT " + pageable.size();

        return Uni.createFrom().completionStage(() -> prepareAndExecutePaged(pagedCql, pageable, params))
                .map(rs -> {
                    List<T> content = StreamSupport.stream(rs.currentPage().spliterator(), false)
                            .map(mapper::map)
                            .toList();

                    String nextStateStr = null;
                    if (rs.hasMorePages()) {
                        var pagingState = rs.getExecutionInfo().getSafePagingState();
                        if (pagingState != null) {
                            nextStateStr = pagingState.toString();
                        }
                    }

                    return new Paged<>(content, -1, nextStateStr);
                });
    }

    // ----------------------------------------------------------
    // Internals
    // ----------------------------------------------------------

    protected Uni<T> executeQueryForOne(String cql, Object... params) {
        return Uni.createFrom().completionStage(() -> prepareAndExecute(cql, params))
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? mapper.map(row) : null;
                })
                .onFailure().transform(err -> new ScyllaQueryException(tableName, cql, params, err));
    }

    protected Multi<T> executeQueryForList(String cql, Object... params) {
        return Multi.createFrom().completionStage(() -> prepareAndExecute(cql, params))
                .onFailure().transform(err -> new ScyllaQueryException(tableName, cql, params, err))
                .onItem().transformToMultiAndConcatenate(
                        rs -> Multi.createFrom()
                                .iterable(() -> rs.currentPage().iterator())
                                .onItem().transform(row -> {
                                    try {
                                        return mapper.map(row);
                                    } catch (Exception e) {
                                        throw new ScyllaMappingException(tableName, "Failed to map row", e);
                                    }
                                }));
    }

    protected Uni<Void> executeUpdate(String cql, Object... params) {
        return Uni.createFrom().completionStage(() -> prepareAndExecute(cql, params))
                .replaceWithVoid()
                .onFailure().transform(err -> new ScyllaWriteException(tableName, cql, params, err));
    }

    protected <R> Uni<R> runScalarQuery(String cql, Function<Row, R> mapperFn, Object... params) {
        return Uni.createFrom().completionStage(() -> prepareAndExecute(cql, params))
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? mapperFn.apply(row) : null;
                })
                .onFailure().transform(err -> new ScyllaQueryException(tableName, cql, params, err));
    }

    private CompletionStage<AsyncResultSet> prepareAndExecute(String cql, Object... params) {
        return prepareCached(cql).thenCompose(ps -> {
            BoundStatement bound = bindParams(ps, params);
            return session.executeAsync(bound).toCompletableFuture();
        });
    }

    private CompletionStage<AsyncResultSet> prepareAndExecutePaged(String cql, Pageable pageable, Object... params) {
        return prepareCached(cql).thenCompose(ps -> {
            BoundStatement bound = bindParams(ps, params);
            bound = bound.setPageSize(pageable.size());
            if (pageable.pagingState() != null) {
                bound = bound.setPagingState(PagingState.fromString(pageable.pagingState()));
            }
            return session.executeAsync(bound).toCompletableFuture();
        });
    }

    private CompletionStage<PreparedStatement> prepareCached(String cql) {
        PreparedStatement cached = preparedStatements.get(cql);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return session.prepareAsync(cql).toCompletableFuture().thenApply(ps -> {
            PreparedStatement existing = preparedStatements.putIfAbsent(cql, ps);
            return existing != null ? existing : ps;
        });
    }

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

    public EntityMapper<T> getEntityMapper() {
        return mapper;
    }

    private Object[] concat(Object[] left, Object[] right) {
        Object[] out = new Object[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
