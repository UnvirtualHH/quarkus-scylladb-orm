package io.quarkiverse.quarkus.scylladb.orm.it.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;

/**
 * A ScyllaDB with client encryption switched on.
 * <p>
 * Testcontainers' {@code ScyllaDBContainer.withSsl} only copies key material into the
 * image without enabling encryption, so the entrypoint appends the
 * {@code client_encryption_options} block to {@code scylla.yaml} before starting the
 * server.
 * <p>
 * {@link #tryStart()} returns {@code null} instead of throwing when the server does not
 * come up. Running a second Seastar process alongside the suite's plaintext ScyllaDB
 * needs AIO capacity ({@code /proc/sys/fs/aio-max-nr}) that a Docker Desktop VM often
 * does not have, and that is a property of the machine, not a defect worth failing a
 * build over. The caller skips in that case.
 */
public final class TlsScyllaServer implements AutoCloseable {

    private static final String IMAGE = "scylladb/scylla:5.2.10";
    private static final int CQL_PORT = 9042;

    private final GenericContainer<?> container;
    private final Path truststore;
    private final int port;

    private TlsScyllaServer(GenericContainer<?> container, Path truststore) {
        this.container = container;
        this.truststore = truststore;
        this.port = container.getMappedPort(CQL_PORT);
    }

    /** @return a running server, or {@code null} if this host cannot host one */
    public static TlsScyllaServer tryStart() {
        GenericContainer<?> container = null;
        try {
            Path workDir = Files.createTempDirectory("scylla-tls");
            TlsMaterial.Material material = TlsMaterial.generate(workDir);

            container = new GenericContainer<>(IMAGE)
                    .withExposedPorts(CQL_PORT)
                    // Copied, not bind-mounted: a temp directory is not shared into the
                    // Docker VM on macOS, and the server then starts without its key.
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(material.certificatePem(), 0644),
                            "/etc/scylla/scylla.cer.pem")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(material.privateKeyPem(), 0644),
                            "/etc/scylla/scylla.key.pem")
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/bash", "-c",
                            "printf '\\nclient_encryption_options:\\n"
                                    + "    enabled: true\\n"
                                    + "    certificate: /etc/scylla/scylla.cer.pem\\n"
                                    + "    keyfile: /etc/scylla/scylla.key.pem\\n"
                                    + "    require_client_auth: false\\n' >> /etc/scylla/scylla.yaml"
                                    + " && exec /docker-entrypoint.py --smp 1 --memory 1G"))
                    .waitingFor(Wait.forLogMessage(".*initialization completed.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(3)));
            container.start();

            return new TlsScyllaServer(container, material.truststore());
        } catch (Exception e) {
            if (container != null) {
                try {
                    container.stop();
                } catch (RuntimeException ignored) {
                    // already gone
                }
            }
            return null;
        }
    }

    /**
     * Builds a session the way {@code CqlSessionProducer} does: a truststore-backed
     * context handed to {@link ProgrammaticSslEngineFactory} together with the hostname
     * validation flag.
     *
     * @param verificationName the name TLS verifies the certificate against; the
     *        connection always goes to loopback
     */
    public CqlSession session(String verificationName, boolean hostnameValidation) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(truststore)) {
            trust.load(in, TlsMaterial.PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return build(verificationName, sslContext, hostnameValidation);
    }

    /** A session that does not trust the server's self-signed certificate. */
    public CqlSession sessionWithoutTrust(String host) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        return build(host, sslContext, true);
    }

    private CqlSession build(String verificationName, SSLContext sslContext, boolean hostnameValidation) {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(loopbackAs(verificationName), port))
                .withLocalDatacenter("datacenter1")
                .withSslEngineFactory(new ProgrammaticSslEngineFactory(sslContext, null, hostnameValidation))
                .build();
    }

    /**
     * Loopback carrying a fixed hostname. Building the address from the literal
     * "127.0.0.1" is not enough: {@code InetSocketAddress} reverse-resolves it, and on a
     * typical machine that yields "localhost" — which the certificate does cover, so the
     * negative assertion would silently pass for the wrong reason.
     */
    private static InetAddress loopbackAs(String hostname) {
        try {
            return InetAddress.getByAddress(hostname, new byte[] { 127, 0, 0, 1 });
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        container.stop();
    }
}
