package io.quarkiverse.quarkus.scylladb.orm.config;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.scylla")
public interface ScyllaOrmConfig {

    /**
     * Comma-separated list of contact points (host:port).
     * Defaults to localhost for local development; override via
     * QUARKUS_SCYLLA_CONTACT_POINTS env var or application.yaml in production.
     */
    @WithDefault("127.0.0.1:9042")
    String contactPoints();

    /**
     * Local datacenter name.
     * Defaults to "datacenter1" which is the standard name for single-DC setups.
     */
    @WithDefault("datacenter1")
    String localDatacenter();

    /**
     * Default keyspace.
     * Must be overridden per service via application.yaml or environment variable.
     */
    @WithDefault("default")
    String keyspace();

    /**
     * Authentication configuration.
     */
    AuthConfig auth();

    /**
     * Connection pool configuration.
     */
    PoolConfig pool();

    /**
     * Request timeout and consistency configuration.
     */
    RequestConfig request();

    /**
     * SSL/TLS configuration.
     */
    SslConfig ssl();

    /**
     * Schema agreement configuration.
     */
    SchemaConfig schema();

    /**
     * Reconnection configuration.
     */
    ReconnectionConfig reconnection();

    /**
     * Authentication settings.
     */
    interface AuthConfig {
        /**
         * Username for plain text authentication.
         */
        Optional<String> username();

        /**
         * Password for plain text authentication.
         */
        Optional<String> password();
    }

    /**
     * Connection pool settings.
     */
    interface PoolConfig {
        /**
         * Number of connections per local host.
         */
        @WithDefault("1")
        int localSize();

        /**
         * Number of connections per remote host.
         */
        @WithDefault("1")
        int remoteSize();

        /**
         * Maximum number of requests per connection.
         */
        @WithDefault("1024")
        int maxRequestsPerConnection();

        /**
         * Heartbeat interval to keep connections alive.
         */
        @WithDefault("30s")
        Duration heartbeatInterval();

        /**
         * Connection initialization timeout.
         */
        @WithDefault("5s")
        Duration connectionInitTimeout();
    }

    /**
     * Request settings.
     */
    interface RequestConfig {
        /**
         * Request timeout.
         */
        @WithDefault("2s")
        Duration timeout();

        /**
         * Default consistency level.
         * Valid values: ANY, ONE, TWO, THREE, QUORUM, ALL, LOCAL_QUORUM, EACH_QUORUM, SERIAL, LOCAL_SERIAL, LOCAL_ONE
         */
        @WithDefault("LOCAL_QUORUM")
        String consistency();

        /**
         * Serial consistency level for lightweight transactions (LWT).
         * Valid values: SERIAL, LOCAL_SERIAL
         */
        @WithDefault("SERIAL")
        String serialConsistency();

        /**
         * Default page size for queries.
         */
        @WithDefault("5000")
        int pageSize();
    }

    /**
     * SSL/TLS settings.
     */
    interface SslConfig {
        /**
         * Enable SSL/TLS.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Path to truststore file (JKS or PKCS12 format).
         */
        Optional<String> truststorePath();

        /**
         * Truststore password.
         */
        Optional<String> truststorePassword();

        /**
         * Path to keystore file for client authentication (JKS or PKCS12 format).
         */
        Optional<String> keystorePath();

        /**
         * Keystore password.
         */
        Optional<String> keystorePassword();

        /**
         * Enable hostname verification.
         */
        @WithDefault("true")
        boolean hostnameValidation();
    }

    /**
     * Schema agreement settings.
     */
    interface SchemaConfig {
        /**
         * Timeout for waiting for schema agreement after schema-altering queries.
         */
        @WithDefault("10s")
        Duration agreementTimeout();

        /**
         * Interval between schema agreement checks.
         */
        @WithDefault("200ms")
        Duration agreementInterval();

        /**
         * Whether to warn if schema agreement fails.
         */
        @WithDefault("true")
        boolean agreementWarnOnFailure();
    }

    /**
     * Reconnection policy settings.
     */
    interface ReconnectionConfig {
        /**
         * Base delay for exponential reconnection policy.
         */
        @WithDefault("1s")
        Duration baseDelay();

        /**
         * Maximum delay for exponential reconnection policy.
         */
        @WithDefault("60s")
        Duration maxDelay();
    }
}
