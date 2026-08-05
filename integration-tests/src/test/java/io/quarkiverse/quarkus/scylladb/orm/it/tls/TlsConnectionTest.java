package io.quarkiverse.quarkus.scylladb.orm.it.tls;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;

import io.quarkiverse.quarkus.scylladb.orm.it.util.TlsScyllaServer;

/**
 * Proves that hostname validation is actually enforced on TLS connections.
 * <p>
 * This is the assertion behind the June 2026 security fix: {@code withSslContext(...)}
 * silently skipped hostname verification, and it was replaced with
 * {@code ProgrammaticSslEngineFactory}, which honours the flag. Until now that rested on
 * a code reading — the ScyllaDB the rest of the suite runs against speaks plaintext.
 * <p>
 * The certificate carries only {@code DNS:localhost}, so reaching the very same server
 * through {@code 127.0.0.1} must fail. That is a sharper check than a happy-path TLS
 * connection: it fails if the flag stops being passed through.
 * <p>
 * Plain JUnit rather than {@code @QuarkusTest}, so it can skip cleanly. A second Seastar
 * process needs AIO capacity that a Docker VM does not always have while the suite's
 * plaintext ScyllaDB is running — see {@link TlsScyllaServer}. The configuration side of
 * the same path is covered deterministically by {@code SslContextTest}.
 * <p>
 * Tagged {@code tls} and excluded from the default build: starting a second ScyllaDB
 * under that contention takes minutes and would turn a one-minute suite into a
 * twenty-minute one. CI runs it in its own job.
 */
@Tag("tls")
class TlsConnectionTest {

    private static TlsScyllaServer server;

    @BeforeAll
    static void startTlsServer() {
        server = TlsScyllaServer.tryStart();
        assumeTrue(server != null,
                "Could not start a second, TLS-enabled ScyllaDB on this host — "
                        + "usually not enough AIO capacity next to the suite's plaintext instance. "
                        + "SslContextTest still covers the configuration side.");
    }

    @AfterAll
    static void stopTlsServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theCoveredNameConnectsWithValidationOn() throws Exception {
        try (CqlSession session = server.session("localhost", true)) {
            assertNotNull(session.execute("SELECT release_version FROM system.local").one());
        }
    }

    @Test
    void hostnameValidationRejectsANameTheCertificateDoesNotCover() {
        // withSslContext(...) would have connected happily here.
        assertThrows(AllNodesFailedException.class, () -> {
            try (CqlSession session = server.session("wrong.example.com", true)) {
                session.execute("SELECT release_version FROM system.local");
            }
        });
    }

    @Test
    void turningHostnameValidationOffAcceptsTheSameConnection() throws Exception {
        // Proves the previous test fails on the hostname and not on trust, the port or
        // the certificate — and shows exactly what the old behaviour allowed through.
        try (CqlSession session = server.session("wrong.example.com", false)) {
            assertNotNull(session.execute("SELECT release_version FROM system.local").one());
        }
    }

    @Test
    void theTruststoreIsWhatMakesTheServerTrusted() {
        // Without the self-signed certificate in a truststore the handshake must fail,
        // so the tests above are not passing because verification is off altogether.
        assertThrows(AllNodesFailedException.class, () -> {
            try (CqlSession session = server.sessionWithoutTrust("localhost")) {
                session.execute("SELECT release_version FROM system.local");
            }
        });
    }
}
