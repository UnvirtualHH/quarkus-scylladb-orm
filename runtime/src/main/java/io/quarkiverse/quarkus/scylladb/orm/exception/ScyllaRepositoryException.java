package io.quarkiverse.quarkus.scylladb.orm.exception;

public abstract class ScyllaRepositoryException extends RuntimeException {
    protected ScyllaRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}