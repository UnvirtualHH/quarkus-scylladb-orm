package io.quarkiverse.quarkus.scylladb.orm.it;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.quarkus.scylladb.orm.it.model.*;
import io.quarkus.runtime.BlockingOperationNotAllowedException;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;

/**
 * Exercises the reflection-sensitive mapping paths over HTTP so that
 * {@code NativeSmokeIT} can drive them against the packaged native binary.
 * <p>
 * A native image can build cleanly and still fail on first use — missing reflection
 * registrations and runtime-initialization mistakes surface when the code runs, not when
 * it is compiled. The {@code @QuarkusTest} suite cannot catch that: it injects beans
 * in-process and never touches the binary.
 */
@Path("/smoke")
@Produces(MediaType.TEXT_PLAIN)
public class SmokeResource {

    @Inject
    ProfileBaseRepository profiles;

    @Inject
    EventBaseRepository events;

    @Inject
    ProfileBaseReactiveRepository reactiveProfiles;

    /** Enum (both modes), collections and a converter — all in one write. */
    @POST
    @Path("/profile")
    @Blocking
    public String createProfile() {
        Profile profile = new Profile();
        profile.setId(UUID.randomUUID());
        profile.setStatus(Profile.Status.SUSPENDED);
        profile.setTier(Profile.Tier.ENTERPRISE);
        profile.setTags(List.of("alpha", "beta"));
        profile.setScores(Set.of(1, 2, 3));
        profile.setAttributes(Map.of("locale", "de"));
        profile.setSettings(new Settings("dark", 14));
        profiles.save(profile);
        return profile.getId().toString();
    }

    @GET
    @Path("/profile/{id}")
    @Blocking
    public String readProfile(@PathParam("id") UUID id) {
        Profile found = profiles.findById(id);
        if (found == null) {
            return "missing";
        }
        return String.join("|",
                found.getStatus().name(),
                found.getTier().name(),
                String.join(",", found.getTags()),
                String.valueOf(found.getScores().size()),
                found.getAttributes().getOrDefault("locale", "?"),
                found.getSettings().theme() + ":" + found.getSettings().fontSize());
    }

    /** Same read through the reactive repository, which has its own mapping path. */
    @GET
    @Path("/profile/{id}/reactive")
    public String readProfileReactive(@PathParam("id") UUID id) {
        Profile found = reactiveProfiles.findById(id).await().indefinitely();
        return found == null ? "missing" : found.getTier().name();
    }

    /** Composite partition and clustering key, plus temporal and numeric columns. */
    @POST
    @Path("/event")
    @Blocking
    public String createEvent() {
        Event event = new Event();
        event.setTenant("smoke");
        event.setDeviceId(UUID.randomUUID());
        event.setOccurredAt(Instant.parse("2026-08-04T12:00:00Z"));
        event.setEventId(UUID.randomUUID());
        event.setEventDay(java.time.LocalDate.of(2026, 8, 4));
        event.setAmount(new java.math.BigDecimal("13.37"));
        events.save(event);
        return event.getDeviceId() + "/" + event.getEventId();
    }

    @GET
    @Path("/event/{deviceId}/{eventId}")
    @Blocking
    public String readEvent(@PathParam("deviceId") UUID deviceId, @PathParam("eventId") UUID eventId) {
        Event found = events.findByKeys("smoke", deviceId, Instant.parse("2026-08-04T12:00:00Z"), eventId);
        return found == null ? "missing" : found.getEventDay() + "|" + found.getAmount();
    }

    /**
     * Deliberately calls the blocking repository from the event loop — returning a
     * {@code Uni} keeps this method on the IO thread. The repository must refuse rather
     * than stall the loop.
     */
    @GET
    @Path("/event-loop-guard")
    public Uni<String> eventLoopGuard() {
        return Uni.createFrom().item(() -> {
            try {
                profiles.findById(UUID.randomUUID());
                return "unguarded";
            } catch (BlockingOperationNotAllowedException expected) {
                return "guarded";
            }
        });
    }

    /** A generated @Query with a structural parameter, guard included. */
    @GET
    @Path("/event/{deviceId}/limited")
    @Blocking
    public String limited(@PathParam("deviceId") UUID deviceId) {
        return String.valueOf(events.findInPartitionLimited("smoke", deviceId, 5).size());
    }
}
