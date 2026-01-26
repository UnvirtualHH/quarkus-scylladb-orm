package io.quarkiverse.quarkus.scylladb.orm.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registry for blocking Repository instances.
 * Provides lookup of repositories by entity type at runtime.
 */
@ApplicationScoped
public class RepositoryRegistry {

    private final Map<Class<?>, Repository<?, ?>> registry = new ConcurrentHashMap<>();

    /**
     * Register a repository for an entity type.
     * Called by generated repositories in @PostConstruct.
     */
    public <T, ID> void register(Class<T> entityType, Repository<T, ID> repository) {
        if (!isRegistered(entityType)) {
            registry.put(entityType, repository);
        }
    }

    /**
     * Get the repository for an entity type.
     *
     * @param entityType The entity class.
     * @return The repository instance.
     * @throws IllegalStateException if no repository is registered.
     */
    @SuppressWarnings("unchecked")
    public <T, ID> Repository<T, ID> get(Class<T> entityType) {
        Repository<?, ?> repo = registry.get(entityType);
        if (repo == null) {
            throw new IllegalStateException("No repository registered for type: " + entityType.getName());
        }
        return (Repository<T, ID>) repo;
    }

    /**
     * Check if a repository is registered for a type.
     */
    public boolean isRegistered(Class<?> type) {
        return registry.containsKey(type);
    }
}
