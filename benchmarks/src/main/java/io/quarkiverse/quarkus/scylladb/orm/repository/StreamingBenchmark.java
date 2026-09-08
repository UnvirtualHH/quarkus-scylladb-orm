package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.Row;

import io.smallrye.mutiny.Multi;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The reactive paging pipeline, per row, with the driver faked out.
 * <p>
 * This is the layer that changed when the push-based emitter was replaced by a
 * demand-driven page source; the numbers here are what tells you whether a future
 * change to it costs anything per row.
 * <p>
 * In this package on purpose: {@code pagesFrom} and {@code rowsOf} are package-private,
 * and widening them to public just to benchmark them would put implementation details
 * into the API.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class StreamingBenchmark {

    /** Page sizes around the configured default of 5000, and a small one. */
    @Param({ "100", "5000" })
    public int rowsPerPage;

    /** Enough pages that the per-page cost is visible next to the per-row cost. */
    private static final int TOTAL_ROWS = 100_000;

    private Multi<String> stream;

    private final class FakePages implements AsyncResultSet {
        private final int index;
        private final int pageCount;

        FakePages(int index, int pageCount) {
            this.index = index;
            this.pageCount = pageCount;
        }

        @Override
        public Iterable<Row> currentPage() {
            return Collections.nCopies(rowsPerPage, ROW);
        }

        @Override
        public boolean hasMorePages() {
            return index < pageCount - 1;
        }

        @Override
        public CompletionStage<AsyncResultSet> fetchNextPage() {
            return CompletableFuture.completedFuture(new FakePages(index + 1, pageCount));
        }

        @Override
        public int remaining() {
            return rowsPerPage;
        }

        @Override
        public ColumnDefinitions getColumnDefinitions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionInfo getExecutionInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean wasApplied() {
            throw new UnsupportedOperationException();
        }
    }

    /** The stream only carries rows; it never reads one. */
    private static final Row ROW = (Row) java.lang.reflect.Proxy.newProxyInstance(
            StreamingBenchmark.class.getClassLoader(), new Class<?>[] { Row.class },
            (p, m, a) -> {
                throw new UnsupportedOperationException(m.getName());
            });

    @org.openjdk.jmh.annotations.Setup
    public void setUp() {
        int pageCount = Math.max(1, TOTAL_ROWS / rowsPerPage);
        stream = ReactiveRepository.rowsOf(
                ReactiveRepository.pagesFrom(
                        () -> CompletableFuture.completedFuture(new FakePages(0, pageCount))),
                row -> "row");
    }

    /**
     * Streaming a full result set with unbounded demand — the shape of
     * {@code repository.query(...).collect().asList()}.
     */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public long streamAllRows() {
        AtomicLong count = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        stream.subscribe().with(item -> count.incrementAndGet(), t -> done.countDown(), done::countDown);
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return count.get();
    }
}
