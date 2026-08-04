package io.quarkiverse.quarkus.scylladb.orm.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the two guards that decide which classes are registered for runtime
 * initialization in a native image.
 * <p>
 * The build steps around them cannot be reached from a JVM-mode test: Quarkus's build
 * graph is demand-driven, and nothing consumes native-image build items unless a native
 * image is actually being built. So the decision logic is tested here, and the steps
 * themselves are covered by the native build in CI.
 * <p>
 * Both guards exist because of real breakage — registering BouncyCastle or Apache HTTP
 * classes that are not on the native-image classpath made GraalVM fail with
 * ClassNotFoundException.
 */
class NativeRegistrationGuardTest {

    @Test
    void matchesAGroupIdByPrefix() {
        List<String> groupIds = List.of("io.quarkus", "org.bouncycastle.something", "com.scylladb");

        assertTrue(ScyllaDBOrmProcessor.hasRuntimeDependency(groupIds, "org.bouncycastle"));
        assertTrue(ScyllaDBOrmProcessor.hasRuntimeDependency(groupIds, "com.scylladb"));
    }

    @Test
    void reportsAbsenceWhenNothingMatches() {
        List<String> groupIds = List.of("io.quarkus", "com.scylladb");

        assertFalse(ScyllaDBOrmProcessor.hasRuntimeDependency(groupIds, "org.bouncycastle"));
        assertFalse(ScyllaDBOrmProcessor.hasRuntimeDependency(groupIds, "org.apache.httpcomponents"));
    }

    @Test
    void anEmptyDependencySetMatchesNothing() {
        assertFalse(ScyllaDBOrmProcessor.hasRuntimeDependency(List.of(), "org.bouncycastle"));
    }

    @Test
    void thePrefixIsNotMatchedInTheMiddleOfAGroupId() {
        // "org.bouncycastle" must not be found in "com.example.org.bouncycastle.fork" -
        // that would register classes the image does not have.
        assertFalse(ScyllaDBOrmProcessor.hasRuntimeDependency(
                List.of("com.example.org.bouncycastle.fork"), "org.bouncycastle"));
    }

    @Test
    void classPresenceIsReportedWithoutThrowing() {
        assertTrue(ScyllaDBOrmProcessor.isOnClasspath("java.lang.String"));
        assertTrue(ScyllaDBOrmProcessor.isOnClasspath(
                "com.datastax.oss.driver.internal.core.session.DefaultSession"));
    }

    @Test
    void anAbsentClassIsReportedRatherThanPropagated() {
        // registerIfPresent is called for optional dependencies, so a missing class is
        // the normal case and must not break augmentation.
        assertFalse(ScyllaDBOrmProcessor.isOnClasspath("com.example.definitely.NotHere"));
    }
}
