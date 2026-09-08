package io.quarkiverse.quarkus.scylladb.orm.repository.util;

/**
 * One page request: how many rows, and where to resume.
 *
 * @param size number of rows per page, at least 1
 * @param pagingState the state carried over from a previous page, or {@code null} for the first
 */
public record Pageable(int size, String pagingState) {

    /**
     * A non-positive page size is rejected rather than passed on. The driver reads
     * {@code setPageSize(0)} as "no paging at all", so {@code findAllPaged} silently
     * turned into an unbounded full-table fetch in a single page — the exact opposite of
     * what the caller asked for, and invisible until it took a coordinator down.
     */
    public Pageable {
        if (size < 1) {
            throw new IllegalArgumentException(
                    "Page size must be at least 1, got: " + size
                            + ". A non-positive page size disables paging in the driver.");
        }
    }

    public static Pageable ofSize(int size) {
        return new Pageable(size, null);
    }

    public static Pageable of(int size, String pagingState) {
        return new Pageable(size, pagingState);
    }
}
