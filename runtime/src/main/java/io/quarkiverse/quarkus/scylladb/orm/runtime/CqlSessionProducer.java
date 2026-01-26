package io.quarkiverse.quarkus.scylladb.orm.runtime;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.datastax.oss.driver.api.core.CqlSession;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;

@ApplicationScoped
public class CqlSessionProducer {

    private final AtomicReference<CqlSession> sessionRef = new AtomicReference<>();

    @Produces
    @ApplicationScoped
    public CqlSession produceSession(ScyllaOrmConfig config) {
        // Thread-safe lazy initialization using AtomicReference
        CqlSession existing = sessionRef.get();
        if (existing != null) {
            return existing;
        }

        CqlSession newSession = createSession(config);
        if (sessionRef.compareAndSet(null, newSession)) {
            return newSession;
        } else {
            // Another thread won the race, close our session and return theirs
            newSession.close();
            return sessionRef.get();
        }
    }

    private CqlSession createSession(ScyllaOrmConfig config) {
        List<InetSocketAddress> contactPoints = parseContactPoints(config.contactPoints());

        return CqlSession.builder()
                .addContactPoints(contactPoints)
                .withLocalDatacenter(config.localDatacenter())
                .withKeyspace(config.keyspace())
                .build();
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
