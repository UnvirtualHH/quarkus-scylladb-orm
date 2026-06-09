package io.quarkiverse.quarkus.scylladb.orm.exception;

public class ScyllaWriteException extends ScyllaRepositoryException {

    public ScyllaWriteException(String table, String cql, Object[] params, Throwable cause) {
        super(buildMessage(table, cql, params, cause), cause);
    }

    private static String buildMessage(String table, String cql, Object[] params, Throwable cause) {
        return "Scylla write failed for table '" + table + "'\n"
                + "CQL: " + cql + "\n"
                + "Params: " + describeParams(params)
                + (cause != null ? ("\nCause: " + cause.getMessage()) : "");
    }
}