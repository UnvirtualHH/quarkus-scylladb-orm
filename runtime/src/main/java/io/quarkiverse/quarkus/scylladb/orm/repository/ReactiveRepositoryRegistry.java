package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registry for ReactiveRepository instances.
 * Provides lookup of reactive repositories by entity type at runtime.
 */
@ApplicationScoped
public class ReactiveRepositoryRegistry {

    private final Map<Class<?>, ReactiveRepository<?, ?>> registry = new ConcurrentHashMap<>();

    /**
     * Register a repository for an entity type.
     * Called by generated repositories in @PostConstruct.
     */
    public <T, ID> void register(Class<T> entityType, ReactiveRepository<T, ID> repository) {
        // putIfAbsent, not check-then-put: the latter is not atomic on a
        // ConcurrentHashMap and two concurrent registrations could both pass the check.
        registry.putIfAbsent(entityType, repository);
    }

    /**
     * Get the repository for an entity type.
     *
     * @param entityType The entity class.
     * @return The repository instance.
     * @throws IllegalStateException if no repository is registered.
     */
    @SuppressWarnings("unchecked")
    public <T, ID> ReactiveRepository<T, ID> get(Class<T> entityType) {
        ReactiveRepository<?, ?> repo = registry.get(entityType);
        if (repo == null) {
            throw new IllegalStateException("No repository registered for type: " + entityType.getName());
        }
        return (ReactiveRepository<T, ID>) repo;
    }

    /**
     * Check if a repository is registered for a type.
     */
    public boolean isRegistered(Class<?> type) {
        return registry.containsKey(type);
    }
}
