package io.quarkiverse.quarkus.scylladb.orm.it.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Address;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AddressBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.Probe;
import io.quarkiverse.quarkus.scylladb.orm.it.model.ProbeBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.TemporalProbe;
import io.quarkiverse.quarkus.scylladb.orm.it.model.TemporalProbeBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Pins down mapping behaviour at the edges: the types that are supported through a
 * dedicated handler, and the ones that deliberately are not. The negative cases are
 * characterization tests — they keep a known limitation discoverable instead of letting
 * it resurface as a mystery at runtime. See REVIEW-2026-08-04.md.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
class MappingLimitationsTest {

    @Inject
    ProbeBaseRepository probeRepository;

    @Inject
    TemporalProbeBaseRepository temporalProbeRepository;

    @Inject
    AddressBaseRepository addressRepository;

    /** {@code byte[]} round-trips through a {@code blob} column via ByteArrayTypeHandler. */
    @Test
    void byteArrayFieldsRoundTrip() {
        Probe probe = new Probe();
        probe.setId(UUID.randomUUID());
        probe.setRaw("hi".getBytes(StandardCharsets.UTF_8));
        probeRepository.save(probe);

        Probe found = probeRepository.findById(probe.getId());

        assertNotNull(found);
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), found.getRaw());
    }

    /** Reading the same row twice must not consume the underlying buffer. */
    @Test
    void byteArrayFieldsSurviveRepeatedReads() {
        Probe probe = new Probe();
        probe.setId(UUID.randomUUID());
        probe.setRaw("repeat".getBytes(StandardCharsets.UTF_8));
        probeRepository.save(probe);

        assertArrayEquals("repeat".getBytes(StandardCharsets.UTF_8), probeRepository.findById(probe.getId()).getRaw());
        assertArrayEquals("repeat".getBytes(StandardCharsets.UTF_8), probeRepository.findById(probe.getId()).getRaw());
    }

    /**
     * {@code LocalDateTime} is advertised in the README but has no
     * {@code TIMESTAMP <-> LocalDateTime} codec — the conversion would need a time zone.
     * Use {@code Instant}, or an explicit {@code @Convert}.
     */
    @Test
    void localDateTimeFieldsAreNotSupported() {
        TemporalProbe probe = new TemporalProbe();
        probe.setId(UUID.randomUUID());
        probe.setCreatedAt(LocalDateTime.of(2026, 8, 4, 12, 0));

        Throwable cause = rootCause(assertThrows(RuntimeException.class, () -> temporalProbeRepository.save(probe)));

        assertInstanceOf(CodecNotFoundException.class, cause);
        assertTrue(cause.getMessage().contains("LocalDateTime"), cause.getMessage());
    }

    /**
     * The generated mapper reads <em>every</em> entity field from the row, so a query
     * that selects a subset of columns and maps back to the entity fails at runtime.
     * Fixed once repositories select an explicit column list (E12/E9).
     */
    @Test
    void partialColumnSelectIntoEntityFails() {
        Address address = new Address();
        address.setId(UUID.randomUUID());
        address.setStreet("Probe Street");
        address.setHousenumber("1");
        addressRepository.save(address);

        Throwable cause = rootCause(assertThrows(RuntimeException.class,
                () -> addressRepository.querySingle("SELECT id FROM address WHERE id = ?", address.getId())));

        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("is not a column in this row"), cause.getMessage());
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
