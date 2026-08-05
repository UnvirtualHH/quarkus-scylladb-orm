package io.quarkiverse.quarkus.scylladb.orm.repository.util;

import java.util.List;

/**
 * One page of results plus the state needed to request the next one.
 * <p>
 * Deliberately carries no total count: Cassandra cannot produce one without a full
 * table scan, and the field used to be hard-coded to {@code -1} on every path — a value
 * that lies is worse than no value. Use {@code count()} if an (expensive) total is
 * genuinely needed.
 */
public record Paged<T>(
        List<T> content,
        String nextPagingState) {

    public boolean hasNextPage() {
        return nextPagingState != null;
    }
}
