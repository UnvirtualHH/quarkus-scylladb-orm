package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.Map;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;

/**
 * Shared CQL parameter binding for the blocking and reactive repositories, so the
 * collection-safe codec resolution lives in exactly one place and cannot diverge.
 */
final class StatementBinder {

    private StatementBinder() {
    }

    /**
     * Binds a write whose CQL has one positional marker per entry of {@code columns},
     * in that order, optionally followed by {@code trailing} values (the WHERE clause
     * parameters of an UPDATE).
     * <p>
     * Columns missing from {@code values} are left <strong>unset</strong> rather than
     * bound to null. In CQL an unset value is not written at all, so this reproduces the
     * "only non-null columns are written" semantics — no tombstones — while keeping the
     * statement text constant. Building the text from the non-null columns instead would
     * produce a different CQL string per null-pattern, and with it one prepared statement
     * (and one PREPARE round trip per node) per combination.
     */
    static BoundStatement bindColumns(CqlSession session, PreparedStatement ps,
            String[] columns, Map<String, Object> values, Object... trailing) {
        BoundStatementBuilder builder = ps.boundStatementBuilder();
        ColumnDefinitions defs = ps.getVariableDefinitions();

        for (int i = 0; i < columns.length; i++) {
            Object value = values.get(columns[i]);
            if (value == null) {
                continue; // leave unset
            }
            builder.set(i, value, codecFor(session, defs, i, value));
        }

        for (int i = 0; i < trailing.length; i++) {
            int index = columns.length + i;
            Object value = trailing[i];
            if (value == null) {
                builder.setToNull(index);
            } else {
                builder.set(index, value, codecFor(session, defs, index, value));
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static TypeCodec<Object> codecFor(CqlSession session, ColumnDefinitions defs, int index, Object value) {
        // Resolved from the declared column type plus the runtime value, so collection
        // and interface values bind correctly — unlike a lookup by value.getClass().
        return (TypeCodec<Object>) session.getContext().getCodecRegistry()
                .codecFor(defs.get(index).getType(), value);
    }

    static BoundStatement bind(CqlSession session, PreparedStatement ps, Object... params) {
        if (params.length == 1 && params[0] instanceof Map<?, ?> map && isNamedParams(ps, map)) {
            return bindNamed(session, ps, map);
        }
        // ps.bind resolves codecs by the statement's declared column types — the
        // canonical, collection-safe binding path; nulls are handled natively.
        return ps.bind(params);
    }

    /**
     * Whether a lone {@link Map} argument is the documented "named parameters" form
     * rather than a positional value for a {@code map<,>} column.
     * <p>
     * Both are legitimate calls — {@code query(cql, Map.of("name", "John"))} binds by
     * name, {@code query("... WHERE attrs = ?", attrs)} binds one map value — and the
     * argument alone cannot tell them apart. Deciding on the type of the value made the
     * second one impossible: its entries were read as parameter names and the driver
     * rejected the first one it did not recognise. The prepared statement knows which
     * names exist, so ask it.
     * <p>
     * A single-entry map keyed exactly like the statement's only marker is still
     * ambiguous; it resolves to named binding, as it always has.
     */
    private static boolean isNamedParams(PreparedStatement ps, Map<?, ?> map) {
        if (map.isEmpty()) {
            return false; // nothing to bind by name; let the positional path report the arity
        }
        ColumnDefinitions defs = ps.getVariableDefinitions();
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !defs.contains(name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Binds named parameters from a Map. The codec is resolved from the prepared
     * statement's declared column type plus the runtime value, so collection/interface
     * values (e.g. an ArrayList for a {@code list<>} column) bind correctly — unlike a
     * lookup by {@code value.getClass()}.
     */
    @SuppressWarnings("unchecked")
    private static BoundStatement bindNamed(CqlSession session, PreparedStatement ps, Map<?, ?> map) {
        BoundStatementBuilder builder = ps.boundStatementBuilder();
        ColumnDefinitions defs = ps.getVariableDefinitions();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = entry.getKey().toString();
            Object value = entry.getValue();
            if (value == null) {
                builder.setToNull(name);
                continue;
            }
            TypeCodec<Object> codec = (TypeCodec<Object>) session.getContext()
                    .getCodecRegistry().codecFor(defs.get(name).getType(), value);
            builder.set(name, value, codec);
        }
        return builder.build();
    }
}
