package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.ClusteringKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;
import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Exercises the mapping paths that no other test entity covers: a composite
 * partition key, a composite clustering key, and the temporal / numeric / blob
 * column types the README advertises.
 */
@Table("event")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Event {

    @PartitionKey(ordinal = 0)
    private String tenant;

    @PartitionKey(ordinal = 1)
    @Column("device_id")
    private UUID deviceId;

    @ClusteringKey(ordinal = 0)
    @Column("occurred_at")
    private Instant occurredAt;

    @ClusteringKey(ordinal = 1)
    @Column("event_id")
    private UUID eventId;

    @Column("event_day")
    private LocalDate eventDay;

    @Column("time_of_day")
    private LocalTime timeOfDay;

    private BigDecimal amount;

    @Column("hit_count")
    private BigInteger hitCount;

    private ByteBuffer payload;

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

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public LocalDate getEventDay() {
        return eventDay;
    }

    public void setEventDay(LocalDate eventDay) {
        this.eventDay = eventDay;
    }

    public LocalTime getTimeOfDay() {
        return timeOfDay;
    }

    public void setTimeOfDay(LocalTime timeOfDay) {
        this.timeOfDay = timeOfDay;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigInteger getHitCount() {
        return hitCount;
    }

    public void setHitCount(BigInteger hitCount) {
        this.hitCount = hitCount;
    }

    public ByteBuffer getPayload() {
        return payload;
    }

    public void setPayload(ByteBuffer payload) {
        this.payload = payload;
    }
}
