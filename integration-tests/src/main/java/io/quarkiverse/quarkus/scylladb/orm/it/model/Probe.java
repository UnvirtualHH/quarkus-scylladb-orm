package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.time.LocalDateTime;
import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;
import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Holds the field types that the mapper generates code for but that the driver has no
 * codec for. Exists purely so
 * {@code io.quarkiverse.quarkus.scylladb.orm.it.mapping.MappingLimitationsTest} can pin
 * that limitation down — see the characterization tests there before changing anything.
 */
@Table("probe")
@GenerateRepository(GenerateRepository.RepositoryType.BLOCKING)
public class Probe {

    @PartitionKey
    private UUID id;

    /** No {@code BLOB <-> byte[]} codec — only {@code ByteBuffer} works today. */
    @Column("raw")
    private byte[] raw;

    /** No {@code TIMESTAMP <-> LocalDateTime} codec — use {@code Instant} instead. */
    @Column("created_at")
    private LocalDateTime createdAt;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
