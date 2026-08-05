package io.quarkiverse.quarkus.scylladb.orm.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;

/**
 * Covers {@code buildSslContext}, which loads the key and trust stores that TLS
 * connections depend on. It had never been executed by any test — the containerised
 * ScyllaDB the suite runs against speaks plaintext — even though the June 2026 security
 * review specifically reworked this path to stop hostname validation being silently
 * skipped.
 */
class SslContextTest {

    @TempDir
    static Path certs;

    private static Path pkcs12Store;
    private static Path jksStore;
    private static final String PASSWORD = "changeit";

    @BeforeAll
    static void createStores() throws Exception {
        pkcs12Store = certs.resolve("store.p12");
        jksStore = certs.resolve("store.jks");
        generateStore(pkcs12Store, "PKCS12");
        generateStore(jksStore, "JKS");
    }

    private static void generateStore(Path target, String type) throws Exception {
        Process process = new ProcessBuilder(
                keytool(),
                "-genkeypair", "-alias", "test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost",
                "-storetype", type,
                "-keystore", target.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(),
                "keytool failed: " + new String(process.getInputStream().readAllBytes()));
        assertTrue(Files.exists(target));
    }

    private static String keytool() {
        Path fromJavaHome = Path.of(System.getProperty("java.home"), "bin", "keytool");
        return Files.exists(fromJavaHome) ? fromJavaHome.toString() : "keytool";
    }

    /** Minimal stand-in for the generated config mapping. */
    private record Ssl(boolean enabled, Optional<String> truststorePath, Optional<String> truststorePassword,
            Optional<String> keystorePath, Optional<String> keystorePassword,
            boolean hostnameValidation) implements ScyllaOrmConfig.SslConfig {

        static Ssl trustOnly(Path store, String password) {
            return new Ssl(true, Optional.of(store.toString()), Optional.ofNullable(password),
                    Optional.empty(), Optional.empty(), true);
        }
    }

    @Test
    void buildsAContextFromAPkcs12Truststore() {
        SSLContext context = CqlSessionProducer.buildSslContext(Ssl.trustOnly(pkcs12Store, PASSWORD));

        assertNotNull(context);
        assertEquals("TLS", context.getProtocol());
    }

    @Test
    void buildsAContextFromAJksTruststore() {
        assertNotNull(CqlSessionProducer.buildSslContext(Ssl.trustOnly(jksStore, PASSWORD)));
    }

    @Test
    @DisplayName("mutual TLS: keystore and truststore are both loaded")
    void buildsAContextForClientAuthentication() {
        SSLContext context = CqlSessionProducer.buildSslContext(new Ssl(true,
                Optional.of(pkcs12Store.toString()), Optional.of(PASSWORD),
                Optional.of(pkcs12Store.toString()), Optional.of(PASSWORD), true));

        assertNotNull(context);
    }

    @Test
    void anEmptyConfigStillYieldsAUsableDefaultContext() {
        // Enabling TLS without stores means "use the JDK defaults", which is a valid
        // setup when the server certificate is signed by a public CA.
        SSLContext context = CqlSessionProducer.buildSslContext(new Ssl(true,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), true));

        assertNotNull(context);
    }

    @Test
    void aWrongStorePasswordFailsLoudly() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> CqlSessionProducer.buildSslContext(Ssl.trustOnly(pkcs12Store, "wrong-password")));

        assertTrue(error.getMessage().contains("Failed to configure SSL context"), error.getMessage());
    }

    @Test
    void aMissingStoreFileFailsLoudly() {
        // Silently falling back to no truststore here would downgrade the connection.
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> CqlSessionProducer.buildSslContext(
                        Ssl.trustOnly(certs.resolve("does-not-exist.p12"), PASSWORD)));

        assertTrue(error.getMessage().contains("Failed to configure SSL context"), error.getMessage());
    }

    @Test
    void theStoreTypeFollowsTheFileExtension() {
        // A .p12 loaded as JKS (or the reverse) fails, so this mapping is what makes the
        // two tests above work at all.
        assertEquals("PKCS12", CqlSessionProducer.detectStoreType(pkcs12Store.toString()));
        assertEquals("JKS", CqlSessionProducer.detectStoreType(jksStore.toString()));
    }
}
