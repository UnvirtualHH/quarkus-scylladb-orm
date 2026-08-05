package io.quarkiverse.quarkus.scylladb.orm.it.util;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Generates the key material a TLS-enabled ScyllaDB needs, using only the JDK — keytool
 * for the key pair, and the standard {@code KeyStore} API to export the PEM files Scylla
 * expects. No openssl, so this works the same on a developer machine and on CI.
 * <p>
 * The certificate deliberately carries <strong>only</strong> {@code DNS:localhost} as a
 * subject alternative name. That is what makes the hostname-validation assertions
 * possible: reaching the same server through {@code 127.0.0.1} must fail validation.
 */
public final class TlsMaterial {

    public static final String PASSWORD = "changeit";

    public record Material(Path certificatePem, Path privateKeyPem, Path truststore) {
    }

    private TlsMaterial() {
    }

    public static Material generate(Path directory) throws Exception {
        Path serverStore = directory.resolve("server.p12");
        runKeytool(
                "-genkeypair", "-alias", "scylla",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost",
                "-storetype", "PKCS12",
                "-keystore", serverStore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD);

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(serverStore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        Certificate certificate = store.getCertificate("scylla");
        PrivateKey privateKey = (PrivateKey) store.getKey("scylla", PASSWORD.toCharArray());

        Path certificatePem = directory.resolve("scylla.cer.pem");
        Path privateKeyPem = directory.resolve("scylla.key.pem");
        writePem(certificatePem, "CERTIFICATE", certificate.getEncoded());
        writePem(privateKeyPem, "PRIVATE KEY", privateKey.getEncoded());

        Path truststore = directory.resolve("truststore.p12");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("scylla", certificate);
        try (OutputStream out = Files.newOutputStream(truststore)) {
            trust.store(out, PASSWORD.toCharArray());
        }

        return new Material(certificatePem, privateKeyPem, truststore);
    }

    private static void writePem(Path target, String label, byte[] der) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        Files.writeString(target, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
        // Scylla runs as a non-root user in the image and has to read these.
        target.toFile().setReadable(true, false);
    }

    private static void runKeytool(String... args) throws Exception {
        Path fromJavaHome = Path.of(System.getProperty("java.home"), "bin", "keytool");
        String keytool = Files.exists(fromJavaHome) ? fromJavaHome.toString() : "keytool";

        String[] command = new String[args.length + 1];
        command[0] = keytool;
        System.arraycopy(args, 0, command, 1, args.length);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: "
                    + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
