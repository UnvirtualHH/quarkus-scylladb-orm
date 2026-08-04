package io.quarkiverse.quarkus.scylladb.orm.it.event;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Event;
import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Pageable;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Paged;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Sortable;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

/**
 * Closes the gap between the two repositories: composite keys, {@code queryPaged},
 * {@code Sortable} and the structural-parameter queries were only ever exercised on the
 * blocking side, even though the reactive one is advertised as first-class.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventReactiveTest {

    @Inject
    EventBaseReactiveRepository eventRepository;

    private static final String TENANT = "reactive-tenant";
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-08-04T08:00:00Z");

    private static final String COLUMNS = "tenant, device_id, occurred_at, event_id, event_day, "
            + "time_of_day, amount, hit_count, payload";
    private static final String PARTITION_CQL = "SELECT " + COLUMNS
            + " FROM event WHERE tenant = :tenant AND device_id = :deviceId";

    private static <T> T await(io.smallrye.mutiny.Uni<T> uni) {
        return uni.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted().getItem();
    }

    @Test
    @Order(1)
    void seed() {
        for (int i = 0; i < 5; i++) {
            Event event = new Event();
            event.setTenant(TENANT);
            event.setDeviceId(DEVICE_ID);
            event.setOccurredAt(BASE.plus(i, ChronoUnit.MINUTES));
            event.setEventId(UUID.randomUUID());
            await(eventRepository.save(event));
        }
    }

    @Test
    @Order(2)
    void findByKeysResolvesTheFullCompositeKey() {
        Event probe = new Event();
        probe.setTenant(TENANT);
        probe.setDeviceId(DEVICE_ID);
        probe.setOccurredAt(BASE);
        probe.setEventId(UUID.randomUUID());
        await(eventRepository.save(probe));

        Event found = await(eventRepository.findByKeys(TENANT, DEVICE_ID, BASE, probe.getEventId()));

        assertNotNull(found);
        assertEquals(probe.getEventId(), found.getEventId());
    }

    @Test
    @Order(3)
    void existsUsesTheFullCompositeKey() {
        Event probe = new Event();
        probe.setTenant(TENANT);
        probe.setDeviceId(DEVICE_ID);
        probe.setOccurredAt(BASE.plus(1, ChronoUnit.MINUTES));
        probe.setEventId(UUID.randomUUID());
        await(eventRepository.save(probe));

        assertTrue(await(eventRepository.exists(probe)));
    }

    @Test
    @Order(4)
    void queryPagedWalksThePartition() {
        Map<String, Object> params = Map.of("tenant", TENANT, "deviceId", DEVICE_ID);

        Paged<Event> first = await(eventRepository.queryPaged(PARTITION_CQL, params, Pageable.ofSize(2), null));

        assertEquals(2, first.content().size());
        assertTrue(first.hasNextPage(), "expected a paging state while more rows remain");

        Paged<Event> second = await(eventRepository.queryPaged(PARTITION_CQL, params,
                Pageable.of(2, first.nextPagingState()), null));

        assertEquals(2, second.content().size());
        assertNotEquals(first.content().get(0).getEventId(), second.content().get(0).getEventId());
    }

    @Test
    @Order(5)
    void queryPagedAppliesSortable() {
        // Sortable is only usable where the partition key is restricted - Scylla rejects
        // ORDER BY on an unrestricted scan - so queryPaged is the path that exercises it.
        Map<String, Object> params = Map.of("tenant", TENANT, "deviceId", DEVICE_ID);

        Paged<Event> ascending = await(eventRepository.queryPaged(PARTITION_CQL, params,
                Pageable.ofSize(10), Sortable.asc("occurred_at")));
        Paged<Event> descending = await(eventRepository.queryPaged(PARTITION_CQL, params,
                Pageable.ofSize(10), Sortable.desc("occurred_at")));

        List<Event> asc = ascending.content();
        List<Event> desc = descending.content();
        assertFalse(asc.isEmpty());
        assertEquals(asc.size(), desc.size());
        assertEquals(asc.get(0).getOccurredAt(), desc.get(desc.size() - 1).getOccurredAt());
    }

    @Test
    @Order(6)
    void structuralParametersWorkOnTheReactiveRepositoryToo() {
        List<Event> limited = eventRepository.findInPartitionLimited(TENANT, DEVICE_ID, 2)
                .subscribe().withSubscriber(io.smallrye.mutiny.helpers.test.AssertSubscriber.create(10))
                .awaitCompletion().getItems();

        assertEquals(2, limited.size());

        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findInPartitionOrdered(TENANT, DEVICE_ID, "occurred_at; DROP TABLE event"));
    }

    @Test
    @Order(7)
    void deleteByKeysRemovesASingleRow() {
        Instant at = BASE.plus(30, ChronoUnit.MINUTES);
        UUID eventId = UUID.randomUUID();

        Event event = new Event();
        event.setTenant(TENANT);
        event.setDeviceId(DEVICE_ID);
        event.setOccurredAt(at);
        event.setEventId(eventId);
        await(eventRepository.save(event));

        await(eventRepository.deleteByKeys(TENANT, DEVICE_ID, at, eventId));

        assertNull(await(eventRepository.findByKeys(TENANT, DEVICE_ID, at, eventId)));
    }
}
