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
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;

@ApplicationScoped
public class CqlSessionProducer {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(CqlSessionProducer.class);

    private final AtomicReference<CqlSession> sessionRef = new AtomicReference<>();

    /**
     * Optional Micrometer registry. Resolvable only when the application provides one
     * (e.g. via the quarkus-micrometer extension); otherwise driver metrics stay off.
     */
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<io.micrometer.core.instrument.MeterRegistry> meterRegistry;

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

        // Metrics are only activated when explicitly enabled AND a MeterRegistry bean is
        // available. Enabling the metric lists without a registry would fail session init.
        boolean metricsActive = config.metrics().enabled() && meterRegistry != null
                && meterRegistry.isResolvable();
        if (config.metrics().enabled() && !metricsActive) {
            LOG.warn("quarkus.scylla.metrics.enabled=true but no MeterRegistry bean is available "
                    + "(add the quarkus-micrometer extension). Driver metrics stay disabled.");
        }

        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoints(contactPoints)
                .withLocalDatacenter(config.localDatacenter())
                .withKeyspace(config.keyspace())
                .withConfigLoader(buildConfigLoader(config, metricsActive));

        if (metricsActive) {
            builder.withMetricRegistry(meterRegistry.get());
        }

        // Authentication — fail fast on partial configuration. Silently connecting
        // without auth (or failing with an opaque server error) because only one of
        // username/password was supplied is a dangerous misconfiguration.
        boolean hasUsername = config.auth().username().isPresent();
        boolean hasPassword = config.auth().password().isPresent();
        if (hasUsername ^ hasPassword) {
            throw new IllegalArgumentException(
                    "ScyllaDB authentication is misconfigured: 'quarkus.scylla.auth.username' and "
                            + "'quarkus.scylla.auth.password' must both be set, or neither.");
        }
        if (hasUsername) {
            builder.withAuthCredentials(
                    config.auth().username().get(),
                    config.auth().password().get());
        }

        // SSL/TLS — use a ProgrammaticSslEngineFactory so that hostname validation is
        // actually applied. withSslContext(SSLContext) disables hostname verification
        // entirely, which leaves TLS connections open to man-in-the-middle attacks.
        if (config.ssl().enabled()) {
            builder.withSslEngineFactory(new ProgrammaticSslEngineFactory(
                    buildSslContext(config.ssl()),
                    null,
                    config.ssl().hostnameValidation()));
        }

        CqlSession session = builder.build();
        warnIfSuperuser(session, config);
        return session;
    }

    /**
     * Least-privilege guardrail: warns if the connected role is a ScyllaDB superuser.
     * Each microservice should connect with its own non-superuser role, GRANTed only on
     * the keyspace it owns. This is a soft check — if the role cannot read system_auth
     * (which is itself good least-privilege posture) the check is silently skipped.
     */
    private void warnIfSuperuser(CqlSession session, ScyllaOrmConfig config) {
        if (config.auth().username().isEmpty()) {
            return; // No auth configured → role cannot be determined here.
        }
        String role = config.auth().username().get();
        try {
            ResultSet rs = session.execute(SimpleStatement
                    .newInstance("SELECT is_superuser FROM system_auth.roles WHERE role = ?", role)
                    .setIdempotent(true));
            Row row = rs.one();
            if (row != null && !row.isNull("is_superuser") && row.getBoolean("is_superuser")) {
                LOG.warnf("ScyllaDB role '%s' is a SUPERUSER. For least-privilege operation, connect "
                        + "each service with a non-superuser role GRANTed only on its own keyspace.", role);
            }
        } catch (Exception e) {
            // No read access to system_auth (good posture) or a different auth backend — skip.
            LOG.debugf("Skipping role privilege check for '%s': %s", role, e.getMessage());
        }
    }

    private DriverConfigLoader buildConfigLoader(ScyllaOrmConfig config, boolean metricsActive) {
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

        // Request throttler (overload protection)
        configureThrottler(loader, config.throttler());

        // Driver metrics via Micrometer (only when a registry is wired in createSession)
        if (metricsActive) {
            loader.withString(DefaultDriverOption.METRICS_FACTORY_CLASS,
                    "com.datastax.oss.driver.internal.metrics.micrometer.MicrometerMetricsFactory");
            loader.withStringList(DefaultDriverOption.METRICS_SESSION_ENABLED, config.metrics().sessionMetrics());
            loader.withStringList(DefaultDriverOption.METRICS_NODE_ENABLED, config.metrics().nodeMetrics());
        }

        return loader.build();
    }

    private void configureThrottler(ProgrammaticDriverConfigLoaderBuilder loader,
            ScyllaOrmConfig.ThrottlerConfig throttler) {
        switch (throttler.type().toLowerCase(java.util.Locale.ROOT)) {
            case "concurrency" -> {
                loader.withString(DefaultDriverOption.REQUEST_THROTTLER_CLASS,
                        "ConcurrencyLimitingRequestThrottler");
                loader.withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_CONCURRENT_REQUESTS,
                        throttler.maxConcurrentRequests());
                loader.withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_QUEUE_SIZE, throttler.maxQueueSize());
            }
            case "rate" -> {
                loader.withString(DefaultDriverOption.REQUEST_THROTTLER_CLASS,
                        "RateLimitingRequestThrottler");
                loader.withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_REQUESTS_PER_SECOND,
                        throttler.maxRequestsPerSecond());
                loader.withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_QUEUE_SIZE, throttler.maxQueueSize());
                // RateLimitingRequestThrottler has no default drain interval; set a small one.
                loader.withDuration(DefaultDriverOption.REQUEST_THROTTLER_DRAIN_INTERVAL,
                        java.time.Duration.ofMillis(10));
            }
            default -> {
                // "none" → driver default PassThroughRequestThrottler (no throttling).
            }
        }
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
        String host;
        String portStr;

        String trimmed = contactPoint.trim();
        if (trimmed.startsWith("[")) {
            // IPv6 bracket notation: [::1]:9042
            int closeBracket = trimmed.indexOf(']');
            if (closeBracket < 0) {
                throw new IllegalArgumentException(
                        "Invalid contact point: '" + contactPoint + "'. Missing closing bracket for IPv6 address.");
            }
            host = trimmed.substring(1, closeBracket);
            String remainder = trimmed.substring(closeBracket + 1);
            if (!remainder.startsWith(":")) {
                throw new IllegalArgumentException(
                        "Invalid contact point: '" + contactPoint + "'. Expected ':port' after closing bracket.");
            }
            portStr = remainder.substring(1);
        } else {
            // IPv4 or hostname: host:port
            int lastColon = trimmed.lastIndexOf(':');
            if (lastColon < 0) {
                throw new IllegalArgumentException(
                        "Invalid contact point format: '" + contactPoint + "'. Expected format: 'host:port' or '[ipv6]:port'");
            }
            host = trimmed.substring(0, lastColon);
            portStr = trimmed.substring(lastColon + 1);
        }

        if (host.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid contact point: '" + contactPoint + "'. Host cannot be empty.");
        }

        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid contact point: '" + contactPoint + "'. Port must be a number, got: '" + portStr + "'");
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
