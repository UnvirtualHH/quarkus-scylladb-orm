package io.quarkiverse.quarkus.scylladb.orm.repository.util;

public record Pageable(int size, String pagingState) {
    public static Pageable ofSize(int size) {
        return new Pageable(size, null);
    }

    public static Pageable of(int size, String pagingState) {
        return new Pageable(size, pagingState);
    }
}