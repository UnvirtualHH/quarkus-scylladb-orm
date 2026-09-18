package io.quarkiverse.quarkus.scylladb.orm.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.datastax.oss.driver.api.core.cql.Row;

/** The mapper registry's lookup side was never exercised — only its write side. */
class EntityMapperRegistryTest {

    private static final class Entity {
    }

    private static final class OtherEntity {
    }

    private static final class StubMapper implements EntityMapper<Entity> {
        @Override
        public Entity map(Row row) {
            return new Entity();
        }

        @Override
        public Map<String, Object> toProperties(Entity entity) {
            return Map.of();
        }

        @Override
        public Class<Entity> getEntityType() {
            return Entity.class;
        }

        @Override
        public String[] getColumnNames() {
            return new String[0];
        }

        @Override
        public String[] getPartitionKeyNames() {
            return new String[0];
        }

        @Override
        public String[] getClusteringKeyNames() {
            return new String[0];
        }

        @Override
        public List<KeyComponent<?>> getPartitionKeyComponents(Entity entity) {
            return List.of();
        }

        @Override
        public List<KeyComponent<?>> getClusteringKeyComponents(Entity entity) {
            return List.of();
        }
    }

    @Test
    void registersAndResolvesByEntityType() {
        EntityMapperRegistry registry = new EntityMapperRegistry();
        StubMapper mapper = new StubMapper();

        assertFalse(registry.isRegistered(Entity.class));
        registry.registerSelf(Entity.class, mapper);

        assertTrue(registry.isRegistered(Entity.class));
        assertSame(mapper, registry.get(Entity.class));
    }

    @Test
    void anUnknownEntityFailsWithANamedMessage() {
        EntityMapperRegistry registry = new EntityMapperRegistry();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.get(OtherEntity.class));

        assertTrue(error.getMessage().contains(OtherEntity.class.getName()), error.getMessage());
    }
}
