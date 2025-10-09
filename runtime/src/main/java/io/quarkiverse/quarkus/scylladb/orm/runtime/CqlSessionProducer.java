package io.quarkiverse.quarkus.scylladb.orm.runtime;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.datastax.oss.driver.api.core.CqlSession;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;

@ApplicationScoped
public class CqlSessionProducer {

    private CqlSession session;

    @Produces
    @ApplicationScoped
    public CqlSession produceSession(ScyllaOrmConfig config) {
        if (session == null) {
            session = CqlSession.builder()
                    .addContactPoints(Arrays.stream(config.contactPoints().split(","))
                            .map(String::trim)
                            .map(cp -> {
                                String[] parts = cp.split(":");
                                return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                            })
                            .collect(Collectors.toList()))
                    .withLocalDatacenter(config.localDatacenter())
                    .withKeyspace(config.keyspace())
                    .build();
        }
        return session;
    }

    @PreDestroy
    void close() {
        if (session != null) {
            session.close();
        }
    }
}
