package io.quarkiverse.quarkus.scylladb.orm.it.event;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Event;
import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Covers the composite partition + clustering key paths and the temporal / numeric /
 * blob column types — none of which were exercised by any test before.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventRepositoryTest {

    @Inject
    EventBaseRepository eventRepository;

    private static final String TENANT = "acme";
    private static final UUID DEVICE_ID = UUID.randomUUID();
    // Scylla stores timestamps at millisecond precision, so truncate to keep the
    // round-trip assertion exact rather than approximately equal.
    private static final Instant OCCURRED_AT = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final UUID EVENT_ID = UUID.randomUUID();

    private static Event newEvent() {
        Event event = new Event();
        event.setTenant(TENANT);
        event.setDeviceId(DEVICE_ID);
        event.setOccurredAt(OCCURRED_AT);
        event.setEventId(EVENT_ID);
        event.setEventDay(LocalDate.of(2026, 8, 4));
        event.setTimeOfDay(LocalTime.of(13, 45, 30));
        event.setAmount(new BigDecimal("1234.5678"));
        event.setHitCount(new BigInteger("90071992547409910"));
        event.setPayload(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        return event;
    }

    @Test
    @Order(1)
    void savePersistsAllColumnTypes() {
        Event saved = eventRepository.save(newEvent());

        assertNotNull(saved);
        assertEquals(TENANT, saved.getTenant());
    }

    @Test
    @Order(2)
    void findByKeysResolvesCompositePartitionAndClusteringKey() {
        Event found = eventRepository.findByKeys(TENANT, DEVICE_ID, OCCURRED_AT, EVENT_ID);

        assertNotNull(found, "composite key lookup returned nothing");
        assertEquals(TENANT, found.getTenant());
        assertEquals(DEVICE_ID, found.getDeviceId());
        assertEquals(OCCURRED_AT, found.getOccurredAt());
        assertEquals(EVENT_ID, found.getEventId());
    }

    @Test
    @Order(3)
    void temporalAndNumericTypesRoundTrip() {
        Event found = eventRepository.findByKeys(TENANT, DEVICE_ID, OCCURRED_AT, EVENT_ID);

        assertNotNull(found);
        assertEquals(LocalDate.of(2026, 8, 4), found.getEventDay());
        assertEquals(LocalTime.of(13, 45, 30), found.getTimeOfDay());
        assertEquals(0, new BigDecimal("1234.5678").compareTo(found.getAmount()));
        assertEquals(new BigInteger("90071992547409910"), found.getHitCount());
    }

    @Test
    @Order(4)
    void blobRoundTrips() {
        Event found = eventRepository.findByKeys(TENANT, DEVICE_ID, OCCURRED_AT, EVENT_ID);

        assertNotNull(found);
        assertNotNull(found.getPayload());
        byte[] bytes = new byte[found.getPayload().remaining()];
        found.getPayload().duplicate().get(bytes);
        assertEquals("hello", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    @Order(5)
    void existsUsesFullCompositeKey() {
        assertTrue(eventRepository.exists(newEvent()));
    }

    @Test
    @Order(6)
    void updateWritesNonKeyColumnsOfACompositeKeyEntity() {
        Event event = newEvent();
        event.setAmount(new BigDecimal("9999.0001"));

        eventRepository.update(event);

        Event found = eventRepository.findByKeys(TENANT, DEVICE_ID, OCCURRED_AT, EVENT_ID);
        assertNotNull(found);
        assertEquals(0, new BigDecimal("9999.0001").compareTo(found.getAmount()));
    }

    @Test
    @Order(7)
    void findByIdRejectsCompositePartitionKeys() {
        // Documented behaviour: findById only supports a single partition key column.
        assertThrows(IllegalStateException.class, () -> eventRepository.findById(DEVICE_ID));
    }

    @Test
    @Order(8)
    void findByKeysRejectsWrongArity() {
        assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findByKeys(TENANT, DEVICE_ID));
    }

    @Test
    @Order(9)
    void deleteRemovesByFullCompositeKey() {
        eventRepository.delete(newEvent());

        assertNull(eventRepository.findByKeys(TENANT, DEVICE_ID, OCCURRED_AT, EVENT_ID));
    }
}
