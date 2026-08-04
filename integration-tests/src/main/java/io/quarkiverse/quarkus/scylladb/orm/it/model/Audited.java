package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Transient;

/**
 * Covers two things nothing else did: a mapped field inherited from a superclass, and
 * {@code @Transient}, which must be excluded from the column list entirely.
 */
@Table("audited")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Audited extends AuditedBase {

    @PartitionKey
    private UUID id;

    private String payload;

    /** Never persisted, and must not appear in any generated statement. */
    @Transient
    private String scratch;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getScratch() {
        return scratch;
    }

    public void setScratch(String scratch) {
        this.scratch = scratch;
    }
}
