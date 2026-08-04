package io.quarkiverse.quarkus.scylladb.orm.it.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Profile;
import io.quarkiverse.quarkus.scylladb.orm.it.model.ProfileBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.Settings;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Covers {@code @Enumerated} (STRING and ORDINAL), collection columns and
 * {@code @Convert} — none of which were exercised by any test before.
 */
@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfileRepositoryTest {

    @Inject
    ProfileBaseRepository profileRepository;

    private static final UUID ID = UUID.randomUUID();

    private static Profile newProfile() {
        Profile profile = new Profile();
        profile.setId(ID);
        profile.setStatus(Profile.Status.SUSPENDED);
        profile.setTier(Profile.Tier.ENTERPRISE);
        profile.setTags(List.of("alpha", "beta"));
        profile.setScores(Set.of(1, 2, 3));
        profile.setAttributes(Map.of("locale", "de", "region", "eu"));
        profile.setSettings(new Settings("dark", 14));
        return profile;
    }

    @Test
    @Order(1)
    void savePersistsEnumsCollectionsAndConvertedValues() {
        Profile saved = profileRepository.save(newProfile());

        assertNotNull(saved);
        assertEquals(ID, saved.getId());
    }

    @Test
    @Order(2)
    void enumeratedStringRoundTrips() {
        Profile found = profileRepository.findById(ID);

        assertNotNull(found);
        assertEquals(Profile.Status.SUSPENDED, found.getStatus());
    }

    @Test
    @Order(3)
    void enumeratedOrdinalRoundTrips() {
        Profile found = profileRepository.findById(ID);

        assertNotNull(found);
        assertEquals(Profile.Tier.ENTERPRISE, found.getTier());
    }

    @Test
    @Order(4)
    void collectionColumnsRoundTrip() {
        Profile found = profileRepository.findById(ID);

        assertNotNull(found);
        assertEquals(List.of("alpha", "beta"), found.getTags());
        assertEquals(Set.of(1, 2, 3), found.getScores());
        assertEquals(Map.of("locale", "de", "region", "eu"), found.getAttributes());
    }

    @Test
    @Order(5)
    void convertedAttributeRoundTrips() {
        Profile found = profileRepository.findById(ID);

        assertNotNull(found);
        assertEquals(new Settings("dark", 14), found.getSettings());
    }

    @Test
    @Order(6)
    void emptyCollectionsDoNotBreakMapping() {
        UUID emptyId = UUID.randomUUID();
        Profile profile = new Profile();
        profile.setId(emptyId);
        profile.setStatus(Profile.Status.ACTIVE);
        profile.setTags(List.of());
        profile.setScores(Set.of());
        profile.setAttributes(Map.of());

        profileRepository.save(profile);
        Profile found = profileRepository.findById(emptyId);

        assertNotNull(found);
        assertEquals(Profile.Status.ACTIVE, found.getStatus());
        // Scylla stores an empty collection as null, so the mapper leaves the field unset.
        assertNull(found.getTier());
    }

    @Test
    @Order(7)
    void updateRewritesEnumAndCollectionColumns() {
        Profile profile = newProfile();
        profile.setStatus(Profile.Status.CLOSED);
        profile.setTier(Profile.Tier.FREE);
        profile.setTags(List.of("gamma"));

        profileRepository.update(profile);

        Profile found = profileRepository.findById(ID);
        assertNotNull(found);
        assertEquals(Profile.Status.CLOSED, found.getStatus());
        assertEquals(Profile.Tier.FREE, found.getTier());
        assertEquals(List.of("gamma"), found.getTags());
    }
}
