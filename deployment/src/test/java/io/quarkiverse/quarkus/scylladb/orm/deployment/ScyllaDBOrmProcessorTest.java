package io.quarkiverse.quarkus.scylladb.orm.deployment;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.spi.CDI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;
import io.quarkiverse.quarkus.scylladb.orm.mapping.EntityMapperRegistry;
import io.quarkiverse.quarkus.scylladb.orm.repository.ReactiveRepositoryRegistry;
import io.quarkiverse.quarkus.scylladb.orm.repository.RepositoryRegistry;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The first test in this module. Its build steps used to be covered only as a side
 * effect of the integration tests, which need Docker and a running ScyllaDB - so a
 * broken registration surfaced late, if at all.
 *
 * Deliberately does not inject a CqlSession: the producer is lazy, so the application
 * boots without a database and this stays a pure augmentation test.
 */
class ScyllaDBOrmProcessorTest {

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.scylla.contact-points", "127.0.0.1:9042")
            .overrideConfigKey("quarkus.scylla.keyspace", "unit_test");

    @Test
    void registriesAreRegisteredAsUnremovableBeans() {
        // registerBeans() marks these unremovable; without that ArC would drop them,
        // since nothing in a minimal application injects them.
        assertNotNull(CDI.current().select(RepositoryRegistry.class).get());
        assertNotNull(CDI.current().select(ReactiveRepositoryRegistry.class).get());
        assertNotNull(CDI.current().select(EntityMapperRegistry.class).get());
    }

    @Test
    void configMappingIsWiredUpAndDefaulted() {
        ScyllaOrmConfig config = CDI.current().select(ScyllaOrmConfig.class).get();

        assertEquals("unit_test", config.keyspace());
        assertEquals("127.0.0.1:9042", config.contactPoints());
        assertEquals("datacenter1", config.localDatacenter());
        assertEquals("LOCAL_QUORUM", config.request().consistency());
        assertEquals(2, config.pool().localSize());
        // Off by default - enabling either has a real cost and must be deliberate.
        assertFalse(config.metrics().enabled());
        assertEquals("none", config.throttler().type());
        assertFalse(config.ssl().enabled());
        assertTrue(config.ssl().hostnameValidation());
    }
}
