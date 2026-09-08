package io.quarkiverse.quarkus.scylladb.orm.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.Row;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;

/**
 * The page source behind the reactive {@code query}/{@code findAll} streams.
 * <p>
 * Its predecessor pushed every row of every page into an unbounded emitter buffer as
 * fast as the driver delivered them — it consulted {@code isCancelled()} but never
 * {@code requested()}, so a slow subscriber did not slow the fetching down, it only
 * grew the heap. What matters here is therefore not "do all the rows arrive" but "is a
 * page fetched before anyone asked for it", which is what these count.
 */
class PagedMultiTest {

    /**
     * A page sequence that counts how often it was asked for the next page. Only the
     * paging methods are implemented — the stream touches nothing else, and letting the
     * rest throw keeps that honest.
     */
    /** The stream never calls anything on a row, it only carries it. */
    private static final Row DUMMY_ROW = (Row) java.lang.reflect.Proxy.newProxyInstance(
            PagedMultiTest.class.getClassLoader(), new Class<?>[] { Row.class },
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    private static class FakePages implements AsyncResultSet {

        private final int pageCount;
        private final int rowsPerPage;
        private final int index;
        private final AtomicInteger fetches;
        /** Which page indices come back empty — Scylla does that on filtered scans. */
        private final java.util.function.IntPredicate empty;

        FakePages(int pageCount, AtomicInteger fetches) {
            this(pageCount, 1, 0, fetches, i -> false);
        }

        FakePages(int pageCount, int rowsPerPage, AtomicInteger fetches) {
            this(pageCount, rowsPerPage, 0, fetches, i -> false);
        }

        FakePages(int pageCount, int rowsPerPage, AtomicInteger fetches, java.util.function.IntPredicate empty) {
            this(pageCount, rowsPerPage, 0, fetches, empty);
        }

        private FakePages(int pageCount, int rowsPerPage, int index, AtomicInteger fetches,
                java.util.function.IntPredicate empty) {
            this.pageCount = pageCount;
            this.rowsPerPage = rowsPerPage;
            this.index = index;
            this.fetches = fetches;
            this.empty = empty;
        }

        @Override
        public Iterable<Row> currentPage() {
            return empty.test(index) ? List.of() : java.util.Collections.nCopies(rowsPerPage, DUMMY_ROW);
        }

        @Override
        public boolean hasMorePages() {
            return index < pageCount - 1;
        }

        @Override
        public CompletionStage<AsyncResultSet> fetchNextPage() {
            fetches.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new FakePages(pageCount, rowsPerPage, index + 1, fetches, empty));
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

    private static Multi<AsyncResultSet> pages(int pageCount, AtomicInteger firstCalls, AtomicInteger fetches) {
        return ReactiveRepository.pagesFrom(() -> {
            firstCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new FakePages(pageCount, fetches));
        });
    }

    @Nested
    @DisplayName("demand")
    class Demand {

        @Test
        void nothingIsFetchedBeforeSubscription() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            pages(100, firstCalls, fetches);

            assertEquals(0, firstCalls.get(), "building the stream must not run a query");
            assertEquals(0, fetches.get());
        }

        @Test
        void subscribingWithoutDemandFetchesNothing() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            pages(100, firstCalls, fetches).subscribe().withSubscriber(AssertSubscriber.create(0));

            assertEquals(0, firstCalls.get(), "no demand, so not even the first page");
            assertEquals(0, fetches.get());
        }

        @Test
        void oneItemOfDemandFetchesOnlyTheFirstPage() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<AsyncResultSet> sub = pages(100, firstCalls, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(1));

            assertEquals(1, sub.getItems().size());
            assertEquals(1, firstCalls.get());
            assertEquals(0, fetches.get(), "the second page must not be fetched before it is asked for");
        }

        @Test
        void furtherPagesArriveOnlyAsTheyAreRequested() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<AsyncResultSet> sub = pages(100, firstCalls, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(1));

            sub.request(2);
            assertEquals(3, sub.getItems().size());
            assertEquals(2, fetches.get(), "three pages consumed, two of them fetched on demand");

            sub.request(5);
            assertEquals(8, sub.getItems().size());
            assertEquals(7, fetches.get());
            assertTrue(fetches.get() < 99, "the remaining pages must still be unfetched");
        }

        @Test
        void unboundedDemandStillStreamsEverything() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<AsyncResultSet> sub = pages(10, firstCalls, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

            sub.assertCompleted();
            assertEquals(10, sub.getItems().size());
            assertEquals(9, fetches.get());
        }

        @Test
        void aSinglePageResultCompletesWithoutFetching() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<AsyncResultSet> sub = pages(1, firstCalls, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

            sub.assertCompleted();
            assertEquals(1, sub.getItems().size());
            assertEquals(0, fetches.get());
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        void cancellingStopsTheFetching() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<AsyncResultSet> sub = pages(100, firstCalls, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(2));
            int fetchedBeforeCancel = fetches.get();

            sub.cancel();
            sub.request(50);

            assertEquals(fetchedBeforeCancel, fetches.get(), "no page may be fetched after cancellation");
        }
    }

    @Nested
    @DisplayName("re-subscription")
    class Resubscription {

        @Test
        void eachSubscriptionStartsFromTheFirstPage() {
            // The state-carrying repeating().uni(Supplier, Function) overload holds one
            // shared state for the lifetime of the Uni, so without the deferred wrapper
            // a second subscriber would resume from where the first one stopped.
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();
            Multi<AsyncResultSet> pages = pages(5, firstCalls, fetches);

            AssertSubscriber<AsyncResultSet> first = pages.subscribe()
                    .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
            AssertSubscriber<AsyncResultSet> second = pages.subscribe()
                    .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

            first.assertCompleted();
            second.assertCompleted();
            assertEquals(5, first.getItems().size());
            assertEquals(5, second.getItems().size(), "the second subscriber must see the whole result too");
            assertEquals(2, firstCalls.get(), "each subscription runs its own query");
        }
    }

    @Nested
    @DisplayName("rows")
    class Rows {

        private Multi<String> rows(int pageCount, int rowsPerPage, AtomicInteger fetches) {
            Multi<AsyncResultSet> pages = ReactiveRepository.pagesFrom(
                    () -> CompletableFuture.completedFuture(new FakePages(pageCount, rowsPerPage, fetches)));
            return ReactiveRepository.rowsOf(pages, row -> "row");
        }

        @Test
        void askingForFewerRowsThanAPageHoldsFetchesNothingFurther() {
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<String> sub = rows(50, 10, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(3));

            assertEquals(3, sub.getItems().size());
            assertEquals(0, fetches.get(), "3 of the first page's 10 rows must not pull page 2");
        }

        @Test
        void demandCrossingAPageBoundaryPullsExactlyOneMorePage() {
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<String> sub = rows(50, 10, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(11));

            assertEquals(11, sub.getItems().size());
            assertEquals(1, fetches.get(), "row 11 lives on page 2, and nothing beyond it was needed");
        }

        @Test
        void demandGrowsTheFetchingOnlyStepByStep() {
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<String> sub = rows(50, 10, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(10));
            assertEquals(0, fetches.get());

            sub.request(10);
            assertEquals(20, sub.getItems().size());
            assertEquals(1, fetches.get());

            sub.request(20);
            assertEquals(40, sub.getItems().size());
            assertEquals(3, fetches.get());
            assertTrue(fetches.get() < 49, "the remaining 46 pages must still be unfetched");
        }

        @Test
        void unboundedDemandStreamsEveryRowOfEveryPage() {
            AtomicInteger fetches = new AtomicInteger();

            AssertSubscriber<String> sub = rows(5, 4, fetches)
                    .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

            sub.assertCompleted();
            assertEquals(20, sub.getItems().size());
            assertEquals(4, fetches.get());
        }

        @Test
        void emptyPagesInTheMiddleDoNotStallTheStream() {
            // Scylla returns empty pages when a filtered scan finds nothing in a range,
            // and still reports more pages. A flatten that waits for an item from every
            // inner stream would sit here forever.
            AtomicInteger fetches = new AtomicInteger();
            Multi<AsyncResultSet> pages = ReactiveRepository.pagesFrom(
                    () -> CompletableFuture.completedFuture(
                            new FakePages(4, 2, fetches, i -> i == 1 || i == 2)));

            AssertSubscriber<String> sub = ReactiveRepository.rowsOf(pages, row -> "row")
                    .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

            sub.assertCompleted();
            assertEquals(4, sub.getItems().size(), "pages 0 and 3 carry two rows each");
            assertEquals(3, fetches.get(), "the empty pages were walked past, not stopped on");
        }

        @Test
        void demandIsCarriedAcrossAnEmptyPage() {
            AtomicInteger fetches = new AtomicInteger();
            Multi<AsyncResultSet> pages = ReactiveRepository.pagesFrom(
                    () -> CompletableFuture.completedFuture(
                            new FakePages(10, 2, fetches, i -> i == 1)));

            AssertSubscriber<String> sub = ReactiveRepository.rowsOf(pages, row -> "row")
                    .subscribe().withSubscriber(AssertSubscriber.create(3));

            // rows 1-2 on page 0, page 1 empty, row 3 on page 2
            assertEquals(3, sub.getItems().size());
            assertEquals(2, fetches.get());
        }

        @Test
        void aMappingFailureIsPropagatedAndStopsTheStream() {
            AtomicInteger fetches = new AtomicInteger();
            Multi<AsyncResultSet> pages = ReactiveRepository.pagesFrom(
                    () -> CompletableFuture.completedFuture(new FakePages(50, 10, fetches)));

            ReactiveRepository.rowsOf(pages, row -> {
                throw new IllegalStateException("bad row");
            }).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
                    .assertFailedWith(IllegalStateException.class, "bad row");

            assertEquals(0, fetches.get(), "a failure must not keep pulling pages");
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        void aFailingFirstPageIsPropagated() {
            Multi<AsyncResultSet> pages = ReactiveRepository.pagesFrom(
                    () -> CompletableFuture.failedFuture(new IllegalStateException("boom")));

            pages.subscribe().withSubscriber(AssertSubscriber.create(1))
                    .assertFailedWith(IllegalStateException.class, "boom");
        }

        @Test
        void aFailingLaterPageIsPropagated() {
            AtomicInteger fetches = new AtomicInteger();
            Multi<AsyncResultSet> pages = ReactiveRepository.pagesFrom(
                    () -> CompletableFuture.completedFuture(new FakePages(3, fetches) {
                        @Override
                        public CompletionStage<AsyncResultSet> fetchNextPage() {
                            return CompletableFuture.failedFuture(new IllegalStateException("page 2 failed"));
                        }
                    }));

            pages.subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
                    .assertFailedWith(IllegalStateException.class, "page 2 failed");
        }
    }
}
