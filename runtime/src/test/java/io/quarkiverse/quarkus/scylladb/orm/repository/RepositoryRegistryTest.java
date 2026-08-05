package io.quarkiverse.quarkus.scylladb.orm.repository;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The registries were only ever written to by generated {@code @PostConstruct} methods;
 * their lookup side and the first-registration-wins rule had no coverage.
 */
class RepositoryRegistryTest {

    private static final class Entity {
    }

    private static final class OtherEntity {
    }

    private static final class TestRepository extends Repository<Entity, Object> {
        @Override
        protected Class<Entity> getEntityType() {
            return Entity.class;
        }
    }

    @Test
    void registersAndResolvesByEntityType() {
        RepositoryRegistry registry = new RepositoryRegistry();
        TestRepository repository = new TestRepository();

        assertFalse(registry.isRegistered(Entity.class));
        registry.register(Entity.class, repository);

        assertTrue(registry.isRegistered(Entity.class));
        assertSame(repository, registry.get(Entity.class));
    }

    @Test
    void theFirstRegistrationWins() {
        // register() is putIfAbsent, so a second registration must not replace the first.
        RepositoryRegistry registry = new RepositoryRegistry();
        TestRepository first = new TestRepository();
        TestRepository second = new TestRepository();

        registry.register(Entity.class, first);
        registry.register(Entity.class, second);

        assertSame(first, registry.get(Entity.class));
    }

    @Test
    void anUnknownEntityFailsWithANamedMessage() {
        RepositoryRegistry registry = new RepositoryRegistry();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.get(OtherEntity.class));

        assertTrue(error.getMessage().contains(OtherEntity.class.getName()), error.getMessage());
    }

    // The reactive registry is a near-copy of the blocking one, so it gets the same
    // checks rather than being trusted to have stayed in sync.

    private static final class TestReactiveRepository extends ReactiveRepository<Entity, Object> {
        @Override
        protected Class<Entity> getEntityType() {
            return Entity.class;
        }
    }

    @Test
    void theReactiveRegistryBehavesIdentically() {
        ReactiveRepositoryRegistry registry = new ReactiveRepositoryRegistry();
        TestReactiveRepository first = new TestReactiveRepository();
        TestReactiveRepository second = new TestReactiveRepository();

        assertFalse(registry.isRegistered(Entity.class));
        registry.register(Entity.class, first);
        registry.register(Entity.class, second);

        assertTrue(registry.isRegistered(Entity.class));
        assertSame(first, registry.get(Entity.class));
        assertThrows(IllegalStateException.class, () -> registry.get(OtherEntity.class));
    }
}
