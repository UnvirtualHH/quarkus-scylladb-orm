package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.enums.EnumType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Convert;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Enumerated;
import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Exercises the remaining uncovered mapping paths: {@code @Enumerated} in both modes,
 * collection columns, and {@code @Convert}.
 */
@Table("profile")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Profile {

    public enum Status {
        ACTIVE,
        SUSPENDED,
        CLOSED
    }

    public enum Tier {
        FREE,
        PRO,
        ENTERPRISE
    }

    @PartitionKey
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.ORDINAL)
    private Tier tier;

    private List<String> tags;

    private Set<Integer> scores;

    private Map<String, String> attributes;

    @Convert(SettingsConverter.class)
    private Settings settings;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public Set<Integer> getScores() {
        return scores;
    }

    public void setScores(Set<Integer> scores) {
        this.scores = scores;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }
}
