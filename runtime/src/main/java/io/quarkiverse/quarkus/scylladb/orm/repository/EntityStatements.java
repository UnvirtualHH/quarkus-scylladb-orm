package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.quarkiverse.quarkus.scylladb.orm.mapping.EntityMapper;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Sortable;

/**
 * The CQL an entity's repository needs, derived once from its mapper.
 * <p>
 * Everything here is <strong>constant per entity</strong> — that is the point. Building
 * statement text per call from whichever columns happen to be non-null produces a
 * different CQL string per null-pattern, and the driver prepares and caches each one
 * separately (plus a PREPARE round trip to every node). An entity with {@code n}
 * optional fields would reach 2^n distinct statements. Instead the column set is fixed
 * and absent values are left unset at bind time; see
 * {@link StatementBinder#bindColumns}.
 * <p>
 * Shared by the blocking and reactive repositories so the two cannot drift apart.
 */
final class EntityStatements {

    /** All mapped columns, in mapper order. */
    final String[] allColumns;

    /** All mapped columns joined for use as an explicit SELECT projection. */
    final String columnList;

    /** Mapped columns that are not part of the primary key — the SET clause of UPDATE. */
    final String[] nonKeyColumns;

    /** {@code INSERT INTO t (...) VALUES (?, ...)} over {@link #allColumns}. */
    final String insertCql;

    /**
     * {@code UPDATE t SET ... WHERE <full key>} over {@link #nonKeyColumns}, or
     * {@code null} when the entity consists solely of key columns and there is nothing
     * to SET.
     */
    final String updateCql;

    /** {@code pk = ? AND ... AND ck = ?} over the full primary key. */
    final String whereFullKey;

    /** The partition key columns, in ordinal order. */
    private final String[] partitionKeys;

    private EntityStatements(String tableName, EntityMapper<?> mapper) {
        this.allColumns = mapper.getColumnNames();
        this.columnList = String.join(", ", allColumns);

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
        this.partitionKeys = pkNames;
        List<String> keyNames = Stream.concat(Arrays.stream(pkNames), Arrays.stream(ckNames)).toList();

        List<String> nonKey = new ArrayList<>(allColumns.length);
        for (String column : allColumns) {
            if (!keyNames.contains(column)) {
                nonKey.add(column);
            }
        }
        this.nonKeyColumns = nonKey.toArray(String[]::new);

        this.insertCql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                tableName,
                columnList,
                Arrays.stream(allColumns).map(c -> "?").collect(Collectors.joining(", ")));

        this.whereFullKey = keyNames.isEmpty()
                ? null
                : keyNames.stream().map(n -> n + " = ?").collect(Collectors.joining(" AND "));

        this.updateCql = nonKey.isEmpty() || whereFullKey == null
                ? null
                : String.format("UPDATE %s SET %s WHERE %s",
                        tableName,
                        nonKey.stream().map(c -> c + " = ?").collect(Collectors.joining(", ")),
                        whereFullKey);
    }

    static EntityStatements of(String tableName, EntityMapper<?> mapper) {
        return new EntityStatements(tableName, mapper);
    }

    /**
     * The WHERE clause over the full primary key, or a failure if the entity declares no
     * key at all — which would otherwise silently produce a full-table statement.
     */
    String requireWhereFullKey(String tableName) {
        if (whereFullKey == null) {
            throw new IllegalStateException("No primary key columns defined for table " + tableName);
        }
        return whereFullKey;
    }

    /**
     * The first partition key column, used by the existence checks as a cheap projection.
     * <p>
     * Indexing straight into the array threw {@code ArrayIndexOutOfBoundsException} for an
     * entity that declares only {@code @ClusteringKey} fields — a table that cannot exist
     * in Scylla, but the resulting error named neither the entity nor the missing
     * annotation. The processor now rejects such an entity at build time; this stays as
     * the guard for a hand-written {@code EntityMapper}.
     */
    String requireFirstPartitionKey(String tableName) {
        if (partitionKeys.length == 0) {
            throw new IllegalStateException(
                    "No @PartitionKey columns defined for table " + tableName
                            + ". Every Scylla table needs at least one partition key column.");
        }
        return partitionKeys[0];
    }

    /**
     * Rejects a {@link Sortable} on a statement that does not restrict the partition key.
     * <p>
     * CQL only allows {@code ORDER BY} once the partition key is restricted by {@code =}
     * or {@code IN}, because rows are only ordered <em>within</em> a partition. A full
     * table scan therefore can never honour one, and the server rejected the statement
     * with a message about the query rather than about the argument that caused it.
     * Scoped queries — {@code queryPaged} with a partition-restricted CQL — are
     * unaffected.
     */
    static void rejectSortOnUnrestrictedScan(Sortable sortable, String method) {
        if (sortable == null || sortable.toCql().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(
                method + " scans the whole table and cannot apply ORDER BY " + sortable.column()
                        + ": CQL only permits ORDER BY when the partition key is restricted by = or IN, "
                        + "since rows are only ordered within a partition. Pass null, or use "
                        + "queryPaged(...) with a CQL statement that restricts the partition key.");
    }
}
