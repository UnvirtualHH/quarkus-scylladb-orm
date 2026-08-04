package io.quarkiverse.quarkus.scylladb.orm.it.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.testcontainers.scylladb.ScyllaDBContainer;

import com.datastax.oss.driver.api.core.CqlSession;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class ScyllaDbTestResource
        implements QuarkusTestResourceLifecycleManager {

    private static final int PORT = 9042;

    private ScyllaDBContainer scylla;

    @Override
    public Map<String, String> start() {
        scylla = new ScyllaDBContainer("scylladb/scylla:5.2.10")
                .withExposedPorts(PORT);

        scylla.start();

        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(scylla.getHost(), scylla.getFirstMappedPort()))
                .withLocalDatacenter("datacenter1")
                .build()) {

            String initCql = Files.readString(Paths.get("src/test/resources/init.cql"));
            for (String stmt : initCql.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    session.execute(trimmed);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load init.cql", e);
        }

        Map<String, String> props = new HashMap<>();
        props.put("quarkus.scylla.contact-points", scylla.getHost() + ":" + scylla.getFirstMappedPort());
        props.put("quarkus.scylla.local-datacenter", "datacenter1");
        props.put("quarkus.scylla.keyspace", "orm_test");
        // The 2s production default is too tight for the full-table COUNT(*) scans the
        // test suite performs against a single containerised node. This is a property of
        // the test environment, not of the extension — so it is raised here rather than
        // via a command line flag, to keep the suite runnable with a plain `mvn verify`.
        props.put("quarkus.scylla.request.timeout", "15s");
        return props;
    }

    @Override
    public void stop() {
        if (scylla != null) {
            scylla.stop();
        }
    }
}
