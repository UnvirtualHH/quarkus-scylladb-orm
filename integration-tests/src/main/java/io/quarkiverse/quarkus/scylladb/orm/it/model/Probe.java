package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;
import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Covers {@code byte[]} against a {@code blob} column, which needs
 * {@code ByteArrayTypeHandler} — the driver itself only maps {@code blob} to
 * {@code ByteBuffer}.
 */
@Table("probe")
@GenerateRepository(GenerateRepository.RepositoryType.BLOCKING)
public class Probe {

    @PartitionKey
    private UUID id;

    @Column("raw")
    private byte[] raw;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public byte[] getRaw() {
        return raw;
    }

    public void setRaw(byte[] raw) {
        this.raw = raw;
    }
}
