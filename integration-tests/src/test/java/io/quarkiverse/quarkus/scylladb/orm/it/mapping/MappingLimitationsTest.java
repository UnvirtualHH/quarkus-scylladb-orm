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
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Characterization tests: these pin down mapping behaviour that is currently
 * <strong>broken or surprising</strong>, so that it cannot silently change and so that
 * the limitation stays discoverable. Each one is expected to be inverted when the
 * corresponding fix lands — see REVIEW-2026-08-04.md.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
class MappingLimitationsTest {

    @Inject
    ProbeBaseRepository probeRepository;

    @Inject
    AddressBaseRepository addressRepository;

    /**
     * {@code byte[]} is advertised in the README but has no {@code BLOB <-> byte[]}
     * codec. Only {@code ByteBuffer} works. Invert this test once byte[] is supported.
     */
    @Test
    void byteArrayFieldsAreNotSupported() {
        Probe probe = new Probe();
        probe.setId(UUID.randomUUID());
        probe.setRaw("hi".getBytes(StandardCharsets.UTF_8));

        Throwable cause = rootCause(assertThrows(RuntimeException.class, () -> probeRepository.save(probe)));

        assertInstanceOf(CodecNotFoundException.class, cause);
        assertTrue(cause.getMessage().contains("BLOB"), cause.getMessage());
    }

    /**
     * {@code LocalDateTime} is advertised in the README but has no
     * {@code TIMESTAMP <-> LocalDateTime} codec — the conversion would need a time zone.
     * Use {@code Instant}, or an explicit {@code @Convert}.
     */
    @Test
    void localDateTimeFieldsAreNotSupported() {
        Probe probe = new Probe();
        probe.setId(UUID.randomUUID());
        probe.setCreatedAt(LocalDateTime.of(2026, 8, 4, 12, 0));

        Throwable cause = rootCause(assertThrows(RuntimeException.class, () -> probeRepository.save(probe)));

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
