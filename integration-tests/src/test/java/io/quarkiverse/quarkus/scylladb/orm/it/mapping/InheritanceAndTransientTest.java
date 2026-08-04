package io.quarkiverse.quarkus.scylladb.orm.it.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Audited;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AuditedBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AuditedMapper;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The generators walk an entity's superclass chain and skip {@code @Transient} fields.
 * Both behaviours were unverified: no test entity had a superclass, and none used
 * {@code @Transient}.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
class InheritanceAndTransientTest {

    @Inject
    AuditedBaseRepository auditedRepository;

    @Inject
    AuditedMapper auditedMapper;

    @Test
    void inheritedFieldsAreMappedAndTransientOnesAreNot() {
        List<String> columns = List.of(auditedMapper.getColumnNames());

        assertTrue(columns.contains("created_by"), "inherited column missing: " + columns);
        assertTrue(columns.contains("payload"), columns.toString());
        assertTrue(columns.contains("id"), columns.toString());
        assertFalse(columns.contains("scratch"), "@Transient field leaked into the column list: " + columns);
    }

    @Test
    void inheritedFieldRoundTrips() {
        Audited entity = new Audited();
        entity.setId(UUID.randomUUID());
        entity.setPayload("body");
        entity.setCreatedBy("fhell");
        auditedRepository.save(entity);

        Audited found = auditedRepository.findById(entity.getId());

        assertNotNull(found);
        assertEquals("body", found.getPayload());
        assertEquals("fhell", found.getCreatedBy(), "inherited column was not persisted");
    }

    @Test
    void transientFieldIsNeverPersisted() {
        Audited entity = new Audited();
        entity.setId(UUID.randomUUID());
        entity.setPayload("body");
        entity.setScratch("must not reach the database");
        auditedRepository.save(entity);

        assertFalse(auditedMapper.toProperties(entity).containsKey("scratch"));

        Audited found = auditedRepository.findById(entity.getId());
        assertNotNull(found);
        assertNull(found.getScratch());
    }
}
