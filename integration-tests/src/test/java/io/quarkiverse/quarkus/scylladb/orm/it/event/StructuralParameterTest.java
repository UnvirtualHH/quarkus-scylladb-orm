package io.quarkiverse.quarkus.scylladb.orm.it.event;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Event;
import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Structural {@code @Query} parameters ({@code :limit}, {@code :order}, {@code :sort})
 * are interpolated into the CQL rather than bound, so the generated guard is the only
 * thing standing between a caller-supplied string and the statement. The whole feature —
 * guard included — had no test at all.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StructuralParameterTest {

    @Inject
    EventBaseRepository eventRepository;

    private static final String TENANT = "structural-tenant";
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-08-04T09:00:00Z");

    @Test
    @Order(1)
    void seed() {
        for (int i = 0; i < 4; i++) {
            Event event = new Event();
            event.setTenant(TENANT);
            event.setDeviceId(DEVICE_ID);
            event.setOccurredAt(BASE.plus(i, ChronoUnit.MINUTES));
            event.setEventId(UUID.randomUUID());
            eventRepository.save(event);
        }
    }

    @Test
    @Order(2)
    void limitIsInterpolatedAndApplied() {
        assertEquals(2, eventRepository.findInPartitionLimited(TENANT, DEVICE_ID, 2).size());
        assertEquals(4, eventRepository.findInPartitionLimited(TENANT, DEVICE_ID, 10).size());
    }

    @Test
    @Order(3)
    void orderIsInterpolatedAndApplied() {
        List<Event> ascending = eventRepository.findInPartitionOrdered(TENANT, DEVICE_ID, "occurred_at ASC");
        List<Event> descending = eventRepository.findInPartitionOrdered(TENANT, DEVICE_ID, "occurred_at DESC");

        assertEquals(4, ascending.size());
        assertEquals(4, descending.size());
        assertEquals(ascending.get(0).getOccurredAt(), descending.get(3).getOccurredAt());
        assertTrue(ascending.get(0).getOccurredAt().isBefore(ascending.get(3).getOccurredAt()));
    }

    @Test
    @Order(4)
    void lowercaseDirectionIsAccepted() {
        assertEquals(4, eventRepository.findInPartitionOrdered(TENANT, DEVICE_ID, "occurred_at desc").size());
    }

    @DisplayName("order injection attempts are rejected before reaching the server")
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "occurred_at ASC; DROP TABLE event",
            "occurred_at ASC, event_id DESC",
            "occurred_at",
            "occurred_at ASCENDING",
            "1 ASC",
            "occurred_at ASC --",
            "(SELECT 1) ASC",
            ""
    })
    @Order(5)
    void rejectsMaliciousOrderValues(String order) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findInPartitionOrdered(TENANT, DEVICE_ID, order));

        assertTrue(error.getMessage().contains("order"), error.getMessage());
    }

    @Test
    @Order(6)
    void rejectsANegativeLimit() {
        // Integer, so the only way through is a negative number - "^\\d+$" rejects it.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findInPartitionLimited(TENANT, DEVICE_ID, -1));

        assertTrue(error.getMessage().contains("-1"), error.getMessage());
    }

    @Test
    @Order(7)
    void rejectsNullStructuralParameters() {
        // A null used to be interpolated as the literal text "null", producing a CQL
        // syntax error from the server instead of naming the actual mistake.
        IllegalArgumentException orderError = assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findInPartitionOrdered(TENANT, DEVICE_ID, null));
        IllegalArgumentException limitError = assertThrows(IllegalArgumentException.class,
                () -> eventRepository.findInPartitionLimited(TENANT, DEVICE_ID, null));

        assertTrue(orderError.getMessage().contains("must not be null"), orderError.getMessage());
        assertTrue(limitError.getMessage().contains("must not be null"), limitError.getMessage());
    }
}
