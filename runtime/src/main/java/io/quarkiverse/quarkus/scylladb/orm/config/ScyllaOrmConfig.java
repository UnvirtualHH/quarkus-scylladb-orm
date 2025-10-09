package io.quarkiverse.quarkus.scylladb.orm.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.scylla")
public interface ScyllaOrmConfig {

    /**
     * Comma-separated list of contact points (host:port).
     */
    String contactPoints();

    /**
     * Local datacenter name.
     */
    String localDatacenter();

    /**
     * Default keyspace.
     */
    String keyspace();
}
