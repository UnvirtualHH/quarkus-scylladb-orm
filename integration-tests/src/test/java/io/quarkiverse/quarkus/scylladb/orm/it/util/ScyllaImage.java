package io.quarkiverse.quarkus.scylladb.orm.it.util;

/**
 * The ScyllaDB image the integration tests run against.
 * <p>
 * Pinned rather than {@code :latest} so a run is reproducible and a broken upstream
 * release cannot turn into a red build on an unrelated commit. Overridable via
 * {@code -Dscylla.image=...} so a version can be tried without editing two files — it
 * used to be hard-coded in both the plaintext and the TLS resource, which is how they
 * drifted onto the same stale tag and stayed there.
 */
public final class ScyllaImage {

    private static final String DEFAULT = "scylladb/scylla:2026.3.4";

    private ScyllaImage() {
    }

    public static String name() {
        return System.getProperty("scylla.image", DEFAULT);
    }
}
