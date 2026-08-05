package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.List;
import java.util.Optional;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import io.quarkiverse.quarkus.scylladb.orm.processor.types.ByteArrayTypeHandler;
import io.quarkiverse.quarkus.scylladb.orm.processor.types.ConverterTypeHandler;
import io.quarkiverse.quarkus.scylladb.orm.processor.types.EnumTypeHandler;

/**
 * Registry for TypeHandler implementations used during annotation processing.
 * This class is stateless and thread-safe - all state is passed through method parameters.
 */
public final class TypeHandlerRegistry {

    // Order matters: the annotation-driven handlers come first so that an explicit
    // @Enumerated / @Convert always wins over the type-driven ones.
    private static final List<TypeHandler> HANDLERS = List.of(
            new EnumTypeHandler(),
            new ConverterTypeHandler(),
            new ByteArrayTypeHandler());

    private TypeHandlerRegistry() {
        // Utility class - prevent instantiation
    }

    /**
     * Finds a handler that supports the given field.
     *
     * @param field the field to find a handler for
     * @param types the Types utility from the processing environment
     * @param elements the Elements utility from the processing environment
     * @return an Optional containing the matching handler, or empty if none found
     */
    public static Optional<TypeHandler> findHandler(VariableElement field, Types types, Elements elements) {
        return HANDLERS.stream().filter(h -> h.supports(field, types, elements)).findFirst();
    }

    /**
     * Finds a handler that supports the given fully-qualified class name.
     *
     * @param fqcn the fully-qualified class name
     * @return an Optional containing the matching handler, or empty if none found
     */
    public static Optional<TypeHandler> findHandler(String fqcn) {
        return HANDLERS.stream().filter(h -> h.supportsType(fqcn)).findFirst();
    }
}
