package io.quarkiverse.quarkus.scylladb.orm.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The registries are filled from generated {@code @PostConstruct} methods, so several
 * beans can register at once during startup. {@code register} was a non-atomic
 * check-then-put until recently; these tests pin the atomicity down rather than trusting
 * that {@code putIfAbsent} stays.
 */
class RegistryConcurrencyTest {

    private static final int THREADS = 16;

    private static final class Entity {
    }

    private static final class TestRepository extends Repository<Entity, Object> {
        @Override
        protected Class<Entity> getEntityType() {
            return Entity.class;
        }
    }

    /**
     * Repeated, because a race either shows up under scheduling pressure or not at all —
     * a single pass proves very little.
     */
    @RepeatedTest(20)
    void concurrentRegistrationsAllResolveToTheSameInstance() throws Exception {
        RepositoryRegistry registry = new RepositoryRegistry();
        List<TestRepository> candidates = java.util.stream.IntStream.range(0, THREADS)
                .mapToObj(i -> new TestRepository()).toList();

        Set<Repository<?, ?>> observed = ConcurrentHashMap.newKeySet();
        CyclicBarrier startTogether = new CyclicBarrier(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (TestRepository candidate : candidates) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    registry.register(Entity.class, candidate);
                    observed.add(registry.get(Entity.class));
                    return null;
                }));
            }

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        // Every thread must have seen the same winner - a lost update would mean two
        // repositories for one entity, and callers silently using different sessions.
        assertEquals(1, observed.size(), "registry handed out more than one instance: " + observed.size());
        assertTrue(candidates.contains(registry.get(Entity.class)));
    }

    @Test
    void aLateRegistrationNeverDisplacesAnEarlierOne() throws Exception {
        RepositoryRegistry registry = new RepositoryRegistry();
        TestRepository first = new TestRepository();
        registry.register(Entity.class, first);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> registry.register(Entity.class, new TestRepository())));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertSame(first, registry.get(Entity.class));
    }

    /**
     * Mirrors the compare-and-set in {@code CqlSessionProducer.produceSession}: whichever
     * thread loses must discard its own instance and use the winner's, or the loser's
     * session leaks with its connection pool.
     */
    @RepeatedTest(20)
    void compareAndSetHandsEveryCallerTheSameInstanceAndLosersDiscardTheirs() throws Exception {
        AtomicReference<Object> ref = new AtomicReference<>();
        Set<Object> handedOut = ConcurrentHashMap.newKeySet();
        Set<Object> discarded = ConcurrentHashMap.newKeySet();
        CyclicBarrier startTogether = new CyclicBarrier(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    Object existing = ref.get();
                    if (existing != null) {
                        handedOut.add(existing);
                        return null;
                    }
                    Object candidate = new Object();
                    if (ref.compareAndSet(null, candidate)) {
                        handedOut.add(candidate);
                    } else {
                        discarded.add(candidate); // stands in for session.close()
                        handedOut.add(ref.get());
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertEquals(1, handedOut.size(), "callers received more than one instance");
        assertFalse(discarded.contains(ref.get()), "the winning instance was discarded");
    }
}
