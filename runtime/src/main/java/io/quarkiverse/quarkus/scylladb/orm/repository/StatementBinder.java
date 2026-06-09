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

    static BoundStatement bind(CqlSession session, PreparedStatement ps, Object... params) {
        if (params.length == 1 && params[0] instanceof Map<?, ?> map) {
            return bindNamed(session, ps, map);
        }
        // ps.bind resolves codecs by the statement's declared column types — the
        // canonical, collection-safe binding path; nulls are handled natively.
        return ps.bind(params);
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
