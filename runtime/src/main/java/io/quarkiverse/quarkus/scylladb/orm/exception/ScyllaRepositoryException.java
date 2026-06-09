package io.quarkiverse.quarkus.scylladb.orm.exception;

public abstract class ScyllaRepositoryException extends RuntimeException {
    protected ScyllaRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Describes bind parameters by count and type only — never by value — so that PII,
     * credentials or other sensitive payloads never leak into exception messages, logs
     * or stack traces.
     */
    protected static String describeParams(Object[] params) {
        if (params == null) {
            return "none";
        }
        if (params.length == 1 && params[0] instanceof java.util.Map<?, ?> map) {
            return map.size() + " named param(s)";
        }
        StringBuilder sb = new StringBuilder().append(params.length).append(" param(s)");
        if (params.length > 0) {
            sb.append(" [");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i] == null ? "null" : params[i].getClass().getSimpleName());
            }
            sb.append(']');
        }
        return sb.toString();
    }
}