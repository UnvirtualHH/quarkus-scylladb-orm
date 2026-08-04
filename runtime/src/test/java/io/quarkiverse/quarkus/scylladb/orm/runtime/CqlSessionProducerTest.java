package io.quarkiverse.quarkus.scylladb.orm.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers the pure configuration logic of {@link CqlSessionProducer}. It runs once at
 * startup, which is exactly when a mistake is most expensive and least convenient to
 * debug — and it had 29% branch coverage, all of it incidental.
 */
class CqlSessionProducerTest {

    @Nested
    @DisplayName("contact point parsing")
    class ContactPoints {

        @Test
        void parsesHostAndPort() {
            InetSocketAddress address = CqlSessionProducer.parseContactPoint("scylla.internal:9042");

            assertEquals("scylla.internal", address.getHostString());
            assertEquals(9042, address.getPort());
        }

        @Test
        void parsesIpv6InBracketNotation() {
            InetSocketAddress address = CqlSessionProducer.parseContactPoint("[::1]:9042");

            assertEquals(9042, address.getPort());
            assertTrue(address.getHostString().contains(":"), address.getHostString());
        }

        @Test
        void splitsAndTrimsAList() {
            List<InetSocketAddress> addresses = CqlSessionProducer
                    .parseContactPoints(" a:9042 , b:9043 ,, c:9044 ");

            assertEquals(3, addresses.size());
            assertEquals("a", addresses.get(0).getHostString());
            assertEquals(9044, addresses.get(2).getPort());
        }

        @Test
        void anUnsetContactPointListIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> CqlSessionProducer.parseContactPoints(null));
            assertThrows(IllegalArgumentException.class, () -> CqlSessionProducer.parseContactPoints("   "));
        }

        @DisplayName("malformed contact points fail with a message naming the input")
        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {
                "scylla.internal", // no port
                ":9042", // no host
                "scylla.internal:", // no port number
                "scylla.internal:abc", // port not numeric
                "scylla.internal:0", // port below range
                "scylla.internal:65536", // port above range
                "[::1", // unterminated IPv6 bracket
                "[::1]9042" // IPv6 without the colon before the port
        })
        void rejectsMalformedInput(String contactPoint) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> CqlSessionProducer.parseContactPoint(contactPoint));

            assertTrue(error.getMessage().contains(contactPoint), error.getMessage());
        }
    }

    @Nested
    @DisplayName("keystore type detection")
    class StoreType {

        @Test
        void picksPkcs12ForP12AndPfx() {
            assertEquals("PKCS12", CqlSessionProducer.detectStoreType("/etc/certs/client.p12"));
            assertEquals("PKCS12", CqlSessionProducer.detectStoreType("/etc/certs/client.pfx"));
        }

        @Test
        void isCaseInsensitive() {
            assertEquals("PKCS12", CqlSessionProducer.detectStoreType("/etc/certs/CLIENT.P12"));
        }

        @Test
        void fallsBackToJks() {
            assertEquals("JKS", CqlSessionProducer.detectStoreType("/etc/certs/truststore.jks"));
            assertEquals("JKS", CqlSessionProducer.detectStoreType("/etc/certs/truststore"));
        }
    }

    @Nested
    @DisplayName("authentication configuration")
    class Auth {

        @Test
        void bothOrNeitherIsFine() {
            assertDoesNotThrow(() -> CqlSessionProducer.validateAuthPair(true, true));
            assertDoesNotThrow(() -> CqlSessionProducer.validateAuthPair(false, false));
        }

        @Test
        void halfConfiguredCredentialsFailFast() {
            // Connecting anonymously because only one half was supplied is a silent
            // security downgrade.
            assertThrows(IllegalArgumentException.class, () -> CqlSessionProducer.validateAuthPair(true, false));
            assertThrows(IllegalArgumentException.class, () -> CqlSessionProducer.validateAuthPair(false, true));
        }

        @Test
        void theMessageNamesBothProperties() {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> CqlSessionProducer.validateAuthPair(true, false));

            assertTrue(error.getMessage().contains("quarkus.scylla.auth.username"), error.getMessage());
            assertTrue(error.getMessage().contains("quarkus.scylla.auth.password"), error.getMessage());
        }
    }
}
