package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.quarkiverse.quarkus.scylladb.orm.mapping.EntityMapper;

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

    private EntityStatements(String tableName, EntityMapper<?> mapper) {
        this.allColumns = mapper.getColumnNames();
        this.columnList = String.join(", ", allColumns);

        String[] pkNames = mapper.getPartitionKeyNames();
        String[] ckNames = mapper.getClusteringKeyNames();
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
}
