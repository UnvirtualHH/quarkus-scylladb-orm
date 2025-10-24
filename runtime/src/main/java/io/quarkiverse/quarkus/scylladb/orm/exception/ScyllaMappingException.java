package io.quarkiverse.quarkus.scylladb.orm.exception;

public class ScyllaMappingException extends ScyllaRepositoryException {

    public ScyllaMappingException(String table, String details, Throwable cause) {
        super("Mapping error in table '" + table + "': " + details, cause);
    }
}