package io.quarkiverse.quarkus.scylladb.orm.exception;

public class ScyllaQueryException extends ScyllaRepositoryException {

    private final String table;
    private final String cql;

    public ScyllaQueryException(String table, String cql, Object[] params, Throwable cause) {
        super(buildMessage(table, cql, params, cause), cause);
        this.table = table;
        this.cql = cql;
    }

    private static String buildMessage(String table, String cql, Object[] params, Throwable cause) {
        return "Scylla query failed for table '" + table + "'\n"
                + "CQL: " + cql + "\n"
                + "Params: " + describeParams(params)
                + (cause != null ? ("\nCause: " + cause.getMessage()) : "");
    }

    public String getTable() {
        return table;
    }

    public String getCql() {
        return cql;
    }
}