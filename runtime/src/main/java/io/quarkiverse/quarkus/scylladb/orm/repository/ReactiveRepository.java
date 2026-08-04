package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.*;
import java.util.concurrent.CompletionStage;
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

    /** Constant per-entity CQL, derived once from the mapper. */
    private final EntityStatements statements;

    /**
     * Required so CDI can subclass this for its client proxy. Leaves every field null,
     * so an instance built through it is unusable — protected to keep it out of reach of
     * application code.
     */
    protected ReactiveRepository() {
        this.session = null;
        this.tableName = null;
        this.mapper = null;
        this.registry = null;
        this.statements = null;
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
        this.statements = mapper != null ? EntityStatements.of(tableName, mapper) : null;
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

    // ----------------------------------------------------------
    // Repository API
    // ----------------------------------------------------------

    public Uni<T> findById(ID id) {
        String[] pkNames = mapper.getPartitionKeyNames();
        if (pkNames.length != 1) {
            return Uni.createFrom().failure(new IllegalStateException("findById requires exactly one partition key."));
        }
        String cql = String.format("SELECT %s FROM %s WHERE %s = ?", statements.columnList, tableName, pkNames[0]);
        return executeQueryForOne(cql, id);
    }

    /**
     * Streams <strong>all</strong> rows of the table. This is an unbounded, cluster-wide
     * scan that will overload coordinators / time out on large tables under load. Prefer
     * {@link #findAll(Pageable, Sortable)} or a partition-scoped {@code @Query} in production.
     */
    public Multi<T> findAll() {
        String cql = "SELECT " + statements.columnList + " FROM " + tableName;
        return executeQueryForList(cql);
    }

    public Multi<T> findAll(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT %s FROM %s %s LIMIT ?",
                statements.columnList, tableName, sortClause);

        return Uni.createFrom().completionStage(() -> prepareAndExecutePaged(cql, pageable, pageable.size()))
                .onItem().transformToMulti(
                        rs -> Multi.createFrom().iterable(() -> rs.currentPage().iterator()))
                .map(mapper::map);
    }

    public Uni<Paged<T>> findAllPaged(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT %s FROM %s %s", statements.columnList, tableName, sortClause);

        return Uni.createFrom().completionStage(() -> prepareAndExecutePaged(cql, pageable))
                .map(this::toPage);
    }

    /**
     * Counts <strong>all</strong> rows via {@code SELECT COUNT(*)}. This is a full,
     * cluster-wide scan that is slow and often times out on large tables — avoid on hot
     * paths in production. Consider maintaining a counter table instead.
     */
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
        String where = statements.requireWhereFullKey(tableName);
        String cql = String.format("SELECT %s FROM %s WHERE %s LIMIT 1",
                pkNames[0], tableName, where);
        return runScalarQuery(cql, row -> Boolean.TRUE, buildKeyParams(entity))
                .map(result -> result != null);
    }

    public Uni<T> save(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);
        return executeColumnWrite(statements.insertCql, statements.allColumns, properties)
                .replaceWith(entity);
    }

    public Uni<T> update(T entity) {
        if (statements.updateCql == null) {
            return Uni.createFrom().item(entity); // entity is all key columns — nothing to SET
        }

        Map<String, Object> properties = mapper.toProperties(entity);
        for (String k : mapper.getPartitionKeyNames())
            properties.remove(k);
        for (String k : mapper.getClusteringKeyNames())
            properties.remove(k);

        if (properties.isEmpty()) {
            return Uni.createFrom().item(entity); // every non-key column is null
        }

        return executeColumnWrite(statements.updateCql, statements.nonKeyColumns, properties,
                buildKeyParams(entity)).replaceWith(entity);
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
        String where = statements.requireWhereFullKey(tableName);
        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);
        return executeUpdate(cql, buildKeyParams(entity));
    }

    public Uni<Void> deleteByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = statements.requireWhereFullKey(tableName);

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
        String where = statements.requireWhereFullKey(tableName);

        int expected = pkNames.length + ckNames.length;
        if (keys.length != expected) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "Expected " + expected + " key parts, got " + keys.length));
        }

        String cql = String.format("SELECT %s FROM %s WHERE %s", statements.columnList, tableName, where);
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

    /**
     * Execute a scalar query and return the first column of the first row as Long.
     * Used by generated @Query methods with ReturnType.SCALAR.
     */
    public Uni<Long> queryScalar(String cql, Object... params) {
        return runScalarQuery(cql, row -> row.getLong(0), params);
    }

    // ----------------------------------------------------------
    // Projection Query Methods
    // ----------------------------------------------------------

    public <R> Uni<R> queryProjection(String cql, Function<Row, R> mapperFn, Object... params) {
        return runScalarQuery(cql, mapperFn, params);
    }

    public <R> Multi<R> queryProjectionList(String cql, Function<Row, R> mapperFn, Object... params) {
        return projectionMulti(cql, mapperFn, params);
    }

    public Uni<Paged<T>> queryPaged(String baseCql, Map<String, Object> params, Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = baseCql + " " + sortClause;

        return Uni.createFrom()
                .completionStage(() -> prepareAndExecutePaged(pagedCql, pageable, params != null ? params : Map.of()))
                .map(this::toPage);
    }

    /** Maps exactly the rows of the current page and carries the state for the next one. */
    private Paged<T> toPage(AsyncResultSet rs) {
        List<T> content = StreamSupport.stream(rs.currentPage().spliterator(), false)
                .map(mapper::map)
                .toList();

        var pagingState = rs.getExecutionInfo().getSafePagingState();
        return new Paged<>(content, pagingState != null ? pagingState.toString() : null);
    }

    // ----------------------------------------------------------
    // Internals
    // ----------------------------------------------------------

    protected Uni<T> executeQueryForOne(String cql, Object... params) {
        return Uni.createFrom().completionStage(() -> prepareAndExecute(true, cql, params))
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? mapper.map(row) : null;
                })
                .onFailure().transform(err -> new ScyllaQueryException(tableName, cql, params, err));
    }

    protected Multi<T> executeQueryForList(String cql, Object... params) {
        return Multi.createFrom().emitter(emitter -> {
            prepareAndExecute(true, cql, params).whenComplete((firstPage, err) -> {
                if (err != null) {
                    emitter.fail(new ScyllaQueryException(tableName, cql, params, err));
                    return;
                }
                emitAllPages(firstPage, emitter, cql, params);
            });
        });
    }

    protected <R> Uni<R> runScalarQuery(String cql, Function<Row, R> mapperFn, Object... params) {
        return Uni.createFrom().completionStage(() -> prepareAndExecute(true, cql, params))
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? mapperFn.apply(row) : null;
                })
                .onFailure().transform(err -> new ScyllaQueryException(tableName, cql, params, err));
    }

    protected <R> Multi<R> projectionMulti(String cql, Function<Row, R> mapperFn, Object... params) {
        return Multi.createFrom().emitter(emitter -> {
            prepareAndExecute(true, cql, params).whenComplete((firstPage, err) -> {
                if (err != null) {
                    emitter.fail(new ScyllaQueryException(tableName, cql, params, err));
                    return;
                }
                emitAllProjectionPages(firstPage, emitter, mapperFn, cql, params);
            });
        });
    }

    /**
     * Executes a write whose statement text is fixed: columns absent from {@code values}
     * are left unset instead of being bound to null, so the CQL — and therefore the
     * prepared statement — stays the same regardless of which fields are populated.
     */
    protected Uni<Void> executeColumnWrite(String cql, String[] columns, Map<String, Object> values,
            Object... keyParams) {
        return Uni.createFrom()
                .completionStage(() -> session.prepareAsync(cql).toCompletableFuture()
                        .thenCompose(ps -> session
                                .executeAsync(StatementBinder.bindColumns(session, ps, columns, values, keyParams))
                                .toCompletableFuture()))
                .replaceWithVoid()
                .onFailure()
                .transform(err -> new ScyllaWriteException(tableName, cql, values.values().toArray(), err));
    }

    protected Uni<Void> executeUpdate(String cql, Object... params) {
        return Uni.createFrom().completionStage(() -> prepareAndExecute(false, cql, params))
                .replaceWithVoid()
                .onFailure().transform(err -> new ScyllaWriteException(tableName, cql, params, err));
    }

    private void emitAllPages(AsyncResultSet rs, io.smallrye.mutiny.subscription.MultiEmitter<? super T> emitter,
            String cql, Object[] params) {
        try {
            for (Row row : rs.currentPage()) {
                if (emitter.isCancelled()) {
                    return;
                }
                emitter.emit(mapper.map(row));
            }
            if (emitter.isCancelled()) {
                return;
            }
            if (rs.hasMorePages()) {
                rs.fetchNextPage().whenComplete((nextPage, err) -> {
                    if (emitter.isCancelled()) {
                        return;
                    }
                    if (err != null) {
                        emitter.fail(new ScyllaQueryException(tableName, cql, params, err));
                    } else {
                        emitAllPages(nextPage, emitter, cql, params);
                    }
                });
            } else {
                emitter.complete();
            }
        } catch (Exception e) {
            emitter.fail(new ScyllaMappingException(tableName, "Failed to map row", e));
        }
    }

    private <R> void emitAllProjectionPages(AsyncResultSet rs,
            io.smallrye.mutiny.subscription.MultiEmitter<? super R> emitter,
            Function<Row, R> mapperFn, String cql, Object[] params) {
        try {
            for (Row row : rs.currentPage()) {
                if (emitter.isCancelled()) {
                    return;
                }
                emitter.emit(mapperFn.apply(row));
            }
            if (emitter.isCancelled()) {
                return;
            }
            if (rs.hasMorePages()) {
                rs.fetchNextPage().whenComplete((nextPage, err) -> {
                    if (emitter.isCancelled()) {
                        return;
                    }
                    if (err != null) {
                        emitter.fail(new ScyllaQueryException(tableName, cql, params, err));
                    } else {
                        emitAllProjectionPages(nextPage, emitter, mapperFn, cql, params);
                    }
                });
            } else {
                emitter.complete();
            }
        } catch (Exception e) {
            emitter.fail(new ScyllaMappingException(tableName, "Failed to map projection row", e));
        }
    }

    private CompletionStage<AsyncResultSet> prepareAndExecute(boolean idempotent, String cql, Object... params) {
        return session.prepareAsync(cql).toCompletableFuture().thenCompose(ps -> {
            BoundStatement bound = StatementBinder.bind(session, ps, params).setIdempotent(idempotent);
            return session.executeAsync(bound).toCompletableFuture();
        });
    }

    private CompletionStage<AsyncResultSet> prepareAndExecutePaged(String cql, Pageable pageable, Object... params) {
        return session.prepareAsync(cql).toCompletableFuture().thenCompose(ps -> {
            // Paged fetches are reads → idempotent.
            BoundStatement bound = StatementBinder.bind(session, ps, params)
                    .setIdempotent(true)
                    .setPageSize(pageable.size());
            if (pageable.pagingState() != null) {
                bound = bound.setPagingState(PagingState.fromString(pageable.pagingState()));
            }
            return session.executeAsync(bound).toCompletableFuture();
        });
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
