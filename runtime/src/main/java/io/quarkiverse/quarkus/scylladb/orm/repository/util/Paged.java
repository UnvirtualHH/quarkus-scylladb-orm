package io.quarkiverse.quarkus.scylladb.orm.repository.util;

import java.util.List;

public record Paged<T>(
        List<T> content,
        long totalElements,
        String nextPagingState) {
    public boolean hasNextPage() {
        return nextPagingState != null;
    }
}