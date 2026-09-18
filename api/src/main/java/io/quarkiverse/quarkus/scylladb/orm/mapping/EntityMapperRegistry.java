package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registry for EntityMapper instances.
 */
@ApplicationScoped
public class EntityMapperRegistry {

    private final Map<Class<?>, EntityMapper<?>> registry = new ConcurrentHashMap<>();

    /**
     * Register a mapper for an entity type.
     * Called by generated mappers in @PostConstruct.
     */
    public <T> void registerSelf(Class<T> type, EntityMapper<T> mapper) {
        registry.put(type, mapper);
    }

    /**
     * Get the mapper for an entity type.
     *
     * @param type The entity class.
     * @return The mapper instance.
     * @throws IllegalStateException if no mapper is registered.
     */
    @SuppressWarnings("unchecked")
    public <T> EntityMapper<T> get(Class<T> type) {
        EntityMapper<?> mapper = registry.get(type);
        if (mapper == null) {
            throw new IllegalStateException("No mapper registered for " + type.getName());
        }
        return (EntityMapper<T>) mapper;
    }

    /**
     * Check if a mapper is registered for a type.
     */
    public boolean isRegistered(Class<?> type) {
        return registry.containsKey(type);
    }
}
