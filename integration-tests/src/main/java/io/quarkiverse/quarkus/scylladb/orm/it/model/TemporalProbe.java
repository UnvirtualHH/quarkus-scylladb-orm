package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.time.LocalDateTime;
import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;
import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Holds the one field type the mapper generates code for but that has no driver codec.
 * Exists so {@code MappingLimitationsTest} can pin the limitation down — see the
 * characterization test there before changing anything.
 */
@Table("temporal_probe")
@GenerateRepository(GenerateRepository.RepositoryType.BLOCKING)
public class TemporalProbe {

    @PartitionKey
    private UUID id;

    /** No {@code TIMESTAMP <-> LocalDateTime} codec — use {@code Instant} instead. */
    @Column("created_at")
    private LocalDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
