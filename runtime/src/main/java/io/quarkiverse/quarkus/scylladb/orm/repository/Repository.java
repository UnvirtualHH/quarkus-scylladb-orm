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
import io.quarkus.runtime.BlockingOperationControl;
import io.quarkus.runtime.BlockingOperationNotAllowedException;

/**
 * Non-reactive repository base for Scylla/Cassandra.
 * Supports composite primary keys (partition + clustering).
 */
public abstract class Repository<T, ID> {

    protected final CqlSession session;
    protected final String tableName;
    protected final EntityMapper<T> mapper;
    protected final RepositoryRegistry registry;

    /** Constant per-entity CQL, derived once from the mapper. */
    private final EntityStatements statements;

    /**
     * Required so CDI can subclass this for its client proxy. Leaves every field null,
     * so an instance built through it is unusable — protected to keep it out of reach of
     * application code.
     */
    protected Repository() {
        this.session = null;
        this.tableName = null;
        this.mapper = null;
        this.registry = null;
        this.statements = null;
    }

    public Repository(CqlSession session, String tableName, EntityMapper<T> mapper, RepositoryRegistry registry) {
        this.session = session;
        this.tableName = tableName;
        this.mapper = mapper;
        this.registry = registry;
        this.statements = mapper != null ? EntityStatements.of(tableName, mapper) : null;
    }

    protected abstract Class<T> getEntityType();

    // ----------------------------------------------------------
    // Helper (keys + where clause)
    // ----------------------------------------------------------

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

    /**
     * Refuses to run a blocking driver call on a Vert.x event loop thread.
     * <p>
     * Every method of this class blocks. Injected into a reactive endpoint it would stall
     * the event loop, and under load that takes the whole application down in a way that
     * is hard to trace back here. Failing fast with a pointer to the fix is the lesser
     * evil — use {@code ReactiveRepository}, or mark the caller {@code @Blocking}.
     * <p>
     * Outside a Quarkus runtime (plain unit tests, a bare JVM) the detector is not
     * installed; anything unexpected there is treated as "blocking is fine", because this
     * guard must never be the reason a legitimate call fails.
     */
    private void assertBlockingAllowed(String cql) {
        boolean allowed;
        try {
            allowed = BlockingOperationControl.isBlockingAllowed();
        } catch (RuntimeException | LinkageError ignored) {
            return; // no Quarkus IO-thread detector available
        }
        if (!allowed) {
            throw new BlockingOperationNotAllowedException(
                    "Blocking ScyllaDB call on the Vert.x event loop thread (table '" + tableName + "'): " + cql
                            + ". Inject the generated ReactiveRepository instead, or annotate the calling method "
                            + "with @io.smallrye.common.annotation.Blocking so Quarkus dispatches it to a worker "
                            + "thread.");
        }
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
        String cql = String.format("SELECT %s FROM %s WHERE %s = ?", statements.columnList, tableName, pkNames[0]);
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
        String where = statements.requireWhereFullKey(tableName);

        int expected = pkNames.length + ckNames.length;
        if (keys.length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " key parts, got " + keys.length);
        }

        String cql = String.format("SELECT %s FROM %s WHERE %s", statements.columnList, tableName, where);
        ResultSet rs = doExecuteQuery(cql, keys);
        Row row = rs.one();
        return row != null ? mapRow(row) : null;
    }

    /**
     * Fetches <strong>all</strong> rows of the table. This is an unbounded, cluster-wide
     * scan that loads the entire table into memory and will overload coordinators / time
     * out on large tables under load. Prefer {@link #findAll(Pageable, Sortable)} or a
     * partition-scoped {@code @Query} in production.
     */
    public List<T> findAll() {
        String cql = "SELECT " + statements.columnList + " FROM " + tableName;
        ResultSet rs = doExecuteQuery(cql);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public List<T> findAll(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT %s FROM %s %s LIMIT ?", statements.columnList, tableName, sortClause);

        ResultSet rs = doExecutePagedQuery(cql, pageable, pageable.size());
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    /**
     * Returns one page plus the state needed to fetch the next one.
     * <p>
     * Deliberately has no {@code LIMIT}: a {@code LIMIT} caps the <em>whole</em> result
     * set, so the server would consider the query complete after the first page and
     * return no paging state — {@link Paged#hasNextPage()} could never be true. Paging
     * is driven purely by the statement's page size.
     */
    public Paged<T> findAllPaged(Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String cql = String.format("SELECT %s FROM %s %s", statements.columnList, tableName, sortClause);

        ResultSet rs = doExecutePagedQuery(cql, pageable);
        return toPage(rs);
    }

    /**
     * Counts <strong>all</strong> rows via {@code SELECT COUNT(*)}. This is a full,
     * cluster-wide scan that is slow and often times out on large tables — avoid on hot
     * paths in production. Consider maintaining a counter table instead.
     */
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
        String where = statements.requireWhereFullKey(tableName);
        String cql = String.format("SELECT %s FROM %s WHERE %s LIMIT 1",
                pkNames[0], tableName, where);
        ResultSet rs = doExecuteQuery(cql, buildKeyParams(mapper, entity));
        return rs.one() != null;
    }

    public T save(T entity) {
        Map<String, Object> properties = mapper.toProperties(entity);
        doExecuteColumnWrite(statements.insertCql, statements.allColumns, properties);
        return entity;
    }

    public T merge(T entity) {
        // INSERT is an upsert in Cassandra
        return save(entity);
    }

    public T update(T entity) {
        if (statements.updateCql == null) {
            return entity; // entity consists solely of key columns — nothing to SET
        }

        Map<String, Object> properties = mapper.toProperties(entity);
        for (String k : mapper.getPartitionKeyNames())
            properties.remove(k);
        for (String k : mapper.getClusteringKeyNames())
            properties.remove(k);

        if (properties.isEmpty()) {
            return entity; // every non-key column is null — nothing to write
        }

        doExecuteColumnWrite(statements.updateCql, statements.nonKeyColumns, properties,
                buildKeyParams(mapper, entity));
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
        String where = statements.requireWhereFullKey(tableName);
        String cql = String.format("DELETE FROM %s WHERE %s", tableName, where);
        doExecuteWrite(cql, buildKeyParams(mapper, entity));
    }

    public void deleteByKeys(Object... keys) {
        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        String where = statements.requireWhereFullKey(tableName);

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
        String pagedCql = String.format("%s %s LIMIT ?", cql, sortClause);

        ResultSet rs = doExecutePagedQuery(pagedCql, pageable, concat(params, new Object[] { pageable.size() }));
        return StreamSupport.stream(rs.spliterator(), false)
                .map(this::mapRow)
                .toList();
    }

    public Paged<T> queryPaged(String baseCql, Map<String, Object> params, Pageable pageable, Sortable sortable) {
        String sortClause = (sortable != null) ? sortable.toCql() : "";
        String pagedCql = baseCql + " " + sortClause;

        ResultSet rs = doExecutePagedQuery(pagedCql, pageable, params != null ? params : Map.of());
        return toPage(rs);
    }

    /**
     * Maps exactly the rows of the current page. Iterating the {@link ResultSet} itself
     * would transparently fetch every following page and defeat paging entirely, so this
     * consumes only what is available without a further round trip.
     */
    private Paged<T> toPage(ResultSet rs) {
        int available = rs.getAvailableWithoutFetching();
        List<T> content = new ArrayList<>(available);
        Iterator<Row> rows = rs.iterator();
        for (int i = 0; i < available && rows.hasNext(); i++) {
            content.add(mapRow(rows.next()));
        }

        var pagingState = rs.getExecutionInfo().getSafePagingState();
        return new Paged<>(content, pagingState != null ? pagingState.toString() : null);
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

    /**
     * Execute a scalar query and return the first column of the first row as Long.
     * Used by generated @Query methods with ReturnType.SCALAR.
     */
    public Long queryScalar(String cql, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? row.getLong(0) : null;
    }

    // ----------------------------------------------------------
    // Projection Query Methods
    // ----------------------------------------------------------

    public <R> R queryProjection(String cql, Function<Row, R> mapperFn, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        Row row = rs.one();
        return row != null ? mapperFn.apply(row) : null;
    }

    public <R> List<R> queryProjectionList(String cql, Function<Row, R> mapperFn, Object... params) {
        ResultSet rs = doExecuteQuery(cql, params);
        return StreamSupport.stream(rs.spliterator(), false)
                .map(mapperFn)
                .toList();
    }

    // ----------------------------------------------------------
    // Execute Methods
    // ----------------------------------------------------------

    public void execute(String cql, Object... params) {
        doExecuteWrite(cql, params);
    }

    // ----------------------------------------------------------
    // Internal
    // ----------------------------------------------------------

    private ResultSet doExecuteQuery(String cql, Object... params) {
        assertBlockingAllowed(cql);
        try {
            PreparedStatement ps = session.prepare(cql);
            // Reads are idempotent: safe to retry and to use speculative execution.
            BoundStatement bound = StatementBinder.bind(session, ps, params).setIdempotent(true);
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    private ResultSet doExecutePagedQuery(String cql, Pageable pageable, Object... params) {
        assertBlockingAllowed(cql);
        try {
            PreparedStatement ps = session.prepare(cql);
            BoundStatement bound = StatementBinder.bind(session, ps, params)
                    .setIdempotent(true)
                    .setPageSize(pageable.size());
            if (pageable.pagingState() != null) {
                bound = bound.setPagingState(PagingState.fromString(pageable.pagingState()));
            }
            return session.execute(bound);
        } catch (Exception e) {
            throw new ScyllaQueryException(tableName, cql, params, e);
        }
    }

    /**
     * Executes a write whose statement text is fixed: columns absent from {@code values}
     * are left unset instead of being bound to null, so the CQL — and therefore the
     * prepared statement — stays the same regardless of which fields are populated.
     */
    private ResultSet doExecuteColumnWrite(String cql, String[] columns, Map<String, Object> values,
            Object... keyParams) {
        assertBlockingAllowed(cql);
        try {
            PreparedStatement ps = session.prepare(cql);
            // Writes are intentionally NOT marked idempotent (see doExecuteWrite).
            return session.execute(StatementBinder.bindColumns(session, ps, columns, values, keyParams));
        } catch (Exception e) {
            throw new ScyllaWriteException(tableName, cql, values.values().toArray(), e);
        }
    }

    private ResultSet doExecuteWrite(String cql, Object... params) {
        assertBlockingAllowed(cql);
        try {
            PreparedStatement ps = session.prepare(cql);
            // Writes are intentionally NOT marked idempotent: LWT / counter updates must
            // not be blindly retried by the driver.
            BoundStatement bound = StatementBinder.bind(session, ps, params);
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
