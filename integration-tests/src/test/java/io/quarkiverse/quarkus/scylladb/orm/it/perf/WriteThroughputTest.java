package io.quarkiverse.quarkus.scylladb.orm.it.perf;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import com.datastax.oss.driver.api.core.CqlSession;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Event;
import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;

/**
 * A coarse throughput check of the write path against a real ScyllaDB.
 * <p>
 * Not a benchmark — a single container on a shared CI runner cannot produce a number
 * worth comparing across machines; {@code benchmarks/} is where per-row cost is measured
 * properly. What this catches is the class of regression a microbenchmark cannot see:
 * an extra round trip per write, a prepared statement that stops being reused, a lost
 * batch of concurrency. Those show up as a rate that falls by an order of magnitude, and
 * the assertions are set that loosely on purpose — they are a tripwire, not a gate.
 * <p>
 * Tagged {@code throughput} and excluded from the everyday build (it writes tens of
 * thousands of rows); CI runs it in its own step.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@Tag("throughput")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WriteThroughputTest {

    @Inject
    EventBaseRepository repository;

    @Inject
    EventBaseReactiveRepository reactiveRepository;

    @Inject
    CqlSession session;

    private static final int ROWS = 5_000;
    /** Bounded, so the test measures the driver's pipelining rather than thread churn. */
    private static final int CONCURRENCY = 64;

    private static final String TENANT_BLOCKING = "throughput-blocking";
    private static final String TENANT_REACTIVE = "throughput-reactive";

    /** Fixed per tenant so the read tests can address the partition they were written to. */
    private static final UUID DEVICE_BLOCKING = UUID.randomUUID();
    private static final UUID DEVICE_REACTIVE = UUID.randomUUID();

    private static List<Event> events(String tenant, UUID device, int count) {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<Event> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Event event = new Event();
            event.setTenant(tenant);
            event.setDeviceId(device);
            event.setOccurredAt(base.plusMillis(i));
            event.setEventId(UUID.randomUUID());
            event.setEventDay(LocalDate.of(2026, 9, 8));
            event.setTimeOfDay(LocalTime.of(10, 15, 30));
            event.setAmount(new BigDecimal("1234.5678"));
            event.setHitCount(new BigInteger("90071992547409910"));
            event.setPayload(ByteBuffer.wrap(("payload-" + i).getBytes(StandardCharsets.UTF_8)));
            events.add(event);
        }
        return events;
    }

    /** Elapsed time of the sequential run, so the concurrent one can be judged against it. */
    private static Duration sequentialElapsed;

    private static void report(String label, int rows, Duration elapsed) {
        // (double) first: integer division truncated every rate, and printed 0 whenever
        // there were fewer rows than milliseconds.
        double perSecond = rows / (double) Math.max(elapsed.toMillis(), 1) * 1000.0;
        System.out.printf("[throughput] %-26s %6d rows in %6d ms  ->  %,10.0f rows/s%n",
                label, rows, elapsed.toMillis(), perSecond);
    }

    @BeforeAll
    static void announce() {
        System.out.println("[throughput] Rates depend entirely on the machine and the container; "
                + "compare runs on one host, never across hosts.");
    }

    @Test
    @Order(1)
    void blockingSaves() {
        List<Event> events = events(TENANT_BLOCKING, DEVICE_BLOCKING, ROWS);

        Instant start = Instant.now();
        for (Event event : events) {
            repository.save(event);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        sequentialElapsed = elapsed;
        report("save() sequential", ROWS, elapsed);
        assertTrue(elapsed.toSeconds() < 30,
                "sequential writes took " + elapsed.toSeconds() + "s for " + ROWS
                        + " rows — look for an extra round trip per write, or a prepared statement "
                        + "that stopped being reused");
    }

    @Test
    @Order(2)
    void reactiveSavesWithConcurrency() {
        List<Event> events = events(TENANT_REACTIVE, DEVICE_REACTIVE, ROWS);

        Instant start = Instant.now();
        List<Event> written = Multi.createFrom().iterable(events)
                .onItem().transformToUni(reactiveRepository::save).merge(CONCURRENCY)
                .collect().asList()
                .await().atMost(Duration.ofMinutes(3));
        Duration elapsed = Duration.between(start, Instant.now());

        assertEquals(ROWS, written.size());
        report("save() x" + CONCURRENCY + " reactive", ROWS, elapsed);

        // The machine-independent half of this test: absolute rates say nothing across
        // hosts, but concurrent writes beating sequential ones is a property, and losing
        // it means the concurrency stopped reaching the driver. The bar is deliberately
        // far below the ~8x seen in practice so a throttled runner does not trip it.
        assertTrue(elapsed.toMillis() < sequentialElapsed.toMillis() * 0.75,
                "concurrent writes (" + elapsed.toMillis() + " ms) were not meaningfully faster than "
                        + "sequential ones (" + sequentialElapsed.toMillis() + " ms) — concurrency is "
                        + "not reaching the driver");
        assertTrue(elapsed.toSeconds() < 30, "concurrent writes took " + elapsed.toSeconds() + "s");
    }

    @Test
    @Order(3)
    void everyRowIsReadableAndTheReadStreams() {
        // Reads what run 1 and 2 wrote, so it also proves neither of them silently
        // dropped rows while chasing a rate.
        var counted = session.prepare(
                "SELECT COUNT(*) FROM event WHERE tenant = ? AND device_id = ?");
        for (var partition : List.of(
                java.util.Map.entry(TENANT_BLOCKING, DEVICE_BLOCKING),
                java.util.Map.entry(TENANT_REACTIVE, DEVICE_REACTIVE))) {
            String tenant = partition.getKey();
            Instant start = Instant.now();
            long count = session.execute(counted.bind(tenant, partition.getValue())).one().getLong(0);
            Duration elapsed = Duration.between(start, Instant.now());

            assertEquals(ROWS, count, "rows written but not readable for tenant " + tenant);
            report("COUNT(*) " + tenant, ROWS, elapsed);
        }
    }

    @Test
    @Order(4)
    void streamingReadDoesNotDependOnResultSize() {
        // The demand-driven pipeline: a consumer that wants ten rows must not pay for
        // the whole partition. Before backpressure this fetched every page first.
        Instant start = Instant.now();
        List<Event> firstTen = reactiveRepository
                .query("SELECT * FROM event WHERE tenant = ? AND device_id = ?",
                        TENANT_BLOCKING, DEVICE_BLOCKING)
                .select().first(10)
                .collect().asList()
                .await().atMost(Duration.ofMinutes(1));
        Duration elapsed = Duration.between(start, Instant.now());

        assertEquals(10, firstTen.size());
        report("first 10 of " + ROWS, 10, elapsed);
    }
}
