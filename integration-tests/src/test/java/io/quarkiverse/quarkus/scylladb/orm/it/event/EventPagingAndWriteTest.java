package io.quarkiverse.quarkus.scylladb.orm.it.event;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Event;
import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Pageable;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Paged;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Covers {@code findAll(Pageable, Sortable)} and {@code queryPaged}, which had no tests
 * at all, plus the null-write semantics that {@code save}/{@code update} rely on now
 * that they bind a fixed column set and leave absent values unset.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventPagingAndWriteTest {

    @Inject
    EventBaseRepository eventRepository;

    private static final String TENANT = "paging-tenant";
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-08-04T10:00:00Z");

    private static final String COLUMNS = "tenant, device_id, occurred_at, event_id, event_day, "
            + "time_of_day, amount, hit_count, payload";

    @Test
    @Order(1)
    void seed() {
        for (int i = 0; i < 5; i++) {
            Event event = new Event();
            event.setTenant(TENANT);
            event.setDeviceId(DEVICE_ID);
            event.setOccurredAt(BASE.plus(i, ChronoUnit.MINUTES));
            event.setEventId(UUID.randomUUID());
            event.setAmount(new BigDecimal(i));
            eventRepository.save(event);
        }
    }

    @Test
    @Order(2)
    void findAllWithPageableAppliesTheLimit() {
        List<Event> page = eventRepository.findAll(Pageable.ofSize(3), null);

        assertEquals(3, page.size());
    }

    @Test
    @Order(3)
    void queryPagedWalksThePartitionWithNamedParams() {
        String cql = "SELECT " + COLUMNS + " FROM event WHERE tenant = :tenant AND device_id = :deviceId";
        Map<String, Object> params = Map.of("tenant", TENANT, "deviceId", DEVICE_ID);

        Paged<Event> first = eventRepository.queryPaged(cql, params, Pageable.ofSize(2), null);

        assertEquals(2, first.content().size());
        assertTrue(first.hasNextPage(), "expected a paging state for a 5-row partition read 2 at a time");

        Paged<Event> second = eventRepository.queryPaged(cql, params,
                Pageable.of(2, first.nextPagingState()), null);

        assertEquals(2, second.content().size());
        assertNotEquals(first.content().get(0).getEventId(), second.content().get(0).getEventId(),
                "the second page must not repeat the first");
    }

    @Test
    @Order(4)
    void differentNullPatternsAllRoundTrip() {
        // save() now binds a fixed column set and leaves absent values unset, so these
        // three shapes share one prepared statement instead of producing three.
        UUID sparseId = UUID.randomUUID();
        Event sparse = new Event();
        sparse.setTenant(TENANT);
        sparse.setDeviceId(DEVICE_ID);
        sparse.setOccurredAt(BASE.plus(10, ChronoUnit.MINUTES));
        sparse.setEventId(sparseId);
        eventRepository.save(sparse);

        UUID richId = UUID.randomUUID();
        Event rich = new Event();
        rich.setTenant(TENANT);
        rich.setDeviceId(DEVICE_ID);
        rich.setOccurredAt(BASE.plus(11, ChronoUnit.MINUTES));
        rich.setEventId(richId);
        rich.setAmount(new BigDecimal("7.5"));
        rich.setEventDay(LocalDate.of(2026, 8, 4));
        eventRepository.save(rich);

        Event foundSparse = eventRepository.findByKeys(TENANT, DEVICE_ID, BASE.plus(10, ChronoUnit.MINUTES), sparseId);
        assertNotNull(foundSparse);
        assertNull(foundSparse.getAmount());
        assertNull(foundSparse.getEventDay());

        Event foundRich = eventRepository.findByKeys(TENANT, DEVICE_ID, BASE.plus(11, ChronoUnit.MINUTES), richId);
        assertNotNull(foundRich);
        assertEquals(0, new BigDecimal("7.5").compareTo(foundRich.getAmount()));
        assertEquals(LocalDate.of(2026, 8, 4), foundRich.getEventDay());
    }

    @Test
    @Order(5)
    void nullFieldsDoNotClearStoredValues() {
        // Documented behaviour: an unset column is not written, so a null field leaves
        // the stored value untouched rather than deleting it.
        Instant at = BASE.plus(20, ChronoUnit.MINUTES);
        UUID eventId = UUID.randomUUID();

        Event full = new Event();
        full.setTenant(TENANT);
        full.setDeviceId(DEVICE_ID);
        full.setOccurredAt(at);
        full.setEventId(eventId);
        full.setAmount(new BigDecimal("42"));
        eventRepository.save(full);

        Event withoutAmount = new Event();
        withoutAmount.setTenant(TENANT);
        withoutAmount.setDeviceId(DEVICE_ID);
        withoutAmount.setOccurredAt(at);
        withoutAmount.setEventId(eventId);
        withoutAmount.setEventDay(LocalDate.of(2026, 1, 1));
        eventRepository.save(withoutAmount);

        Event found = eventRepository.findByKeys(TENANT, DEVICE_ID, at, eventId);
        assertNotNull(found);
        assertEquals(0, new BigDecimal("42").compareTo(found.getAmount()), "null must not clear the column");
        assertEquals(LocalDate.of(2026, 1, 1), found.getEventDay());
    }

    @Test
    @Order(6)
    void updateLeavesNullColumnsUntouched() {
        Instant at = BASE.plus(30, ChronoUnit.MINUTES);
        UUID eventId = UUID.randomUUID();

        Event full = new Event();
        full.setTenant(TENANT);
        full.setDeviceId(DEVICE_ID);
        full.setOccurredAt(at);
        full.setEventId(eventId);
        full.setAmount(new BigDecimal("1"));
        full.setEventDay(LocalDate.of(2026, 5, 5));
        eventRepository.save(full);

        Event patch = new Event();
        patch.setTenant(TENANT);
        patch.setDeviceId(DEVICE_ID);
        patch.setOccurredAt(at);
        patch.setEventId(eventId);
        patch.setAmount(new BigDecimal("2"));
        eventRepository.update(patch);

        Event found = eventRepository.findByKeys(TENANT, DEVICE_ID, at, eventId);
        assertNotNull(found);
        assertEquals(0, new BigDecimal("2").compareTo(found.getAmount()));
        assertEquals(LocalDate.of(2026, 5, 5), found.getEventDay(), "null must not clear the column");
    }
}
