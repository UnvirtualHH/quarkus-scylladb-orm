package io.quarkiverse.quarkus.scylladb.orm.runtime;

import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;

@ApplicationScoped
public class CqlSessionProducer {

    private final AtomicReference<CqlSession> sessionRef = new AtomicReference<>();

    @Produces
    @ApplicationScoped
    public CqlSession produceSession(ScyllaOrmConfig config) {
        CqlSession existing = sessionRef.get();
        if (existing != null) {
            return existing;
        }

        CqlSession newSession = createSession(config);
        if (sessionRef.compareAndSet(null, newSession)) {
            return newSession;
        } else {
            newSession.close();
            return sessionRef.get();
        }
    }

    private CqlSession createSession(ScyllaOrmConfig config) {
        List<InetSocketAddress> contactPoints = parseContactPoints(config.contactPoints());

        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoints(contactPoints)
                .withLocalDatacenter(config.localDatacenter())
                .withKeyspace(config.keyspace())
                .withConfigLoader(buildConfigLoader(config));

        // Authentication
        if (config.auth().username().isPresent() && config.auth().password().isPresent()) {
            builder.withAuthCredentials(
                    config.auth().username().get(),
                    config.auth().password().get());
        }

        // SSL/TLS
        if (config.ssl().enabled()) {
            builder.withSslContext(buildSslContext(config.ssl()));
        }

        return builder.build();
    }

    private DriverConfigLoader buildConfigLoader(ScyllaOrmConfig config) {
        ProgrammaticDriverConfigLoaderBuilder loader = DriverConfigLoader.programmaticBuilder();

        // Connection pool settings
        loader.withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, config.pool().localSize());
        loader.withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, config.pool().remoteSize());
        loader.withInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS, config.pool().maxRequestsPerConnection());
        loader.withDuration(DefaultDriverOption.HEARTBEAT_INTERVAL, config.pool().heartbeatInterval());
        loader.withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, config.pool().connectionInitTimeout());

        // Request settings
        loader.withDuration(DefaultDriverOption.REQUEST_TIMEOUT, config.request().timeout());
        loader.withString(DefaultDriverOption.REQUEST_CONSISTENCY, config.request().consistency());
        loader.withString(DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY, config.request().serialConsistency());
        loader.withInt(DefaultDriverOption.REQUEST_PAGE_SIZE, config.request().pageSize());

        // Schema agreement settings
        loader.withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, config.schema().agreementTimeout());
        loader.withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_INTERVAL, config.schema().agreementInterval());
        loader.withBoolean(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_WARN, config.schema().agreementWarnOnFailure());

        // Reconnection policy (exponential)
        loader.withString(DefaultDriverOption.RECONNECTION_POLICY_CLASS, "ExponentialReconnectionPolicy");
        loader.withDuration(DefaultDriverOption.RECONNECTION_BASE_DELAY, config.reconnection().baseDelay());
        loader.withDuration(DefaultDriverOption.RECONNECTION_MAX_DELAY, config.reconnection().maxDelay());

        return loader.build();
    }

    private SSLContext buildSslContext(ScyllaOrmConfig.SslConfig sslConfig) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");

            TrustManagerFactory tmf = null;
            if (sslConfig.truststorePath().isPresent()) {
                KeyStore trustStore = KeyStore.getInstance(detectStoreType(sslConfig.truststorePath().get()));
                char[] trustPassword = sslConfig.truststorePassword().map(String::toCharArray).orElse(null);
                try (FileInputStream fis = new FileInputStream(sslConfig.truststorePath().get())) {
                    trustStore.load(fis, trustPassword);
                }
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
            }

            KeyManagerFactory kmf = null;
            if (sslConfig.keystorePath().isPresent()) {
                KeyStore keyStore = KeyStore.getInstance(detectStoreType(sslConfig.keystorePath().get()));
                char[] keyPassword = sslConfig.keystorePassword().map(String::toCharArray).orElse(null);
                try (FileInputStream fis = new FileInputStream(sslConfig.keystorePath().get())) {
                    keyStore.load(fis, keyPassword);
                }
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, keyPassword);
            }

            sslContext.init(
                    kmf != null ? kmf.getKeyManagers() : null,
                    tmf != null ? tmf.getTrustManagers() : null,
                    null);

            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure SSL context", e);
        }
    }

    private String detectStoreType(String path) {
        return path.toLowerCase().endsWith(".p12") || path.toLowerCase().endsWith(".pfx")
                ? "PKCS12"
                : "JKS";
    }

    private List<InetSocketAddress> parseContactPoints(String contactPointsConfig) {
        if (contactPointsConfig == null || contactPointsConfig.isBlank()) {
            throw new IllegalArgumentException(
                    "ScyllaDB contact points not configured. Set 'quarkus.scylla.contact-points' property.");
        }

        return Arrays.stream(contactPointsConfig.split(","))
                .map(String::trim)
                .filter(cp -> !cp.isEmpty())
                .map(this::parseContactPoint)
                .toList();
    }

    private InetSocketAddress parseContactPoint(String contactPoint) {
        String[] parts = contactPoint.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid contact point format: '" + contactPoint + "'. Expected format: 'host:port'");
        }

        String host = parts[0].trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid contact point: '" + contactPoint + "'. Host cannot be empty.");
        }

        int port;
        try {
            port = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid contact point: '" + contactPoint + "'. Port must be a number, got: '" + parts[1] + "'");
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Invalid contact point: '" + contactPoint + "'. Port must be between 1 and 65535, got: " + port);
        }

        return new InetSocketAddress(host, port);
    }

    @PreDestroy
    void close() {
        CqlSession session = sessionRef.get();
        if (session != null) {
            session.close();
        }
    }
}
