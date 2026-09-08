package io.quarkiverse.quarkus.scylladb.orm.bench.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.enums.EnumType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.ClusteringKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Convert;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Enumerated;
import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Deliberately wide: a composite key plus every mapper path that costs something —
 * temporal types, a converter, both {@code @Enumerated} modes and the three collection
 * kinds. A narrow entity would benchmark the easy case.
 */
@Table("bench_event")
@GenerateRepository(GenerateRepository.RepositoryType.BLOCKING)
public class BenchEvent {

    public enum Status {
        NEW,
        DONE
    }

    public enum Tier {
        FREE,
        PRO
    }

    @PartitionKey(ordinal = 0)
    private String tenant;

    @PartitionKey(ordinal = 1)
    @Column("device_id")
    private UUID deviceId;

    @ClusteringKey(ordinal = 0)
    @Column("occurred_at")
    private Instant occurredAt;

    private String name;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.ORDINAL)
    private Tier tier;

    private List<String> tags;

    private Map<String, String> attributes;

    @Convert(BenchSettingsConverter.class)
    private BenchSettings settings;

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public BenchSettings getSettings() {
        return settings;
    }

    public void setSettings(BenchSettings settings) {
        this.settings = settings;
    }
}
