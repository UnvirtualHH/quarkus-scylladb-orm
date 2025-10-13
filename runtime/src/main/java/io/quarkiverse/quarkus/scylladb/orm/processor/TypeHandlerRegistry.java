package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.List;
import java.util.Optional;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.quarkus.scylladb.orm.processor.types.ConverterTypeHandler;
import io.quarkiverse.quarkus.scylladb.orm.processor.types.EnumTypeHandler;

@ApplicationScoped
public class TypeHandlerRegistry {

    private static final List<TypeHandler> handlers = List.of(
            new EnumTypeHandler(),
            new ConverterTypeHandler());

    private static Types types;
    private static Elements elements;

    public static void init(ProcessingEnvironment env) {
        types = env.getTypeUtils();
        elements = env.getElementUtils();
    }

    public static Optional<TypeHandler> findHandler(VariableElement field, Types types, Elements elements) {
        return handlers.stream().filter(h -> h.supports(field, types, elements)).findFirst();
    }

    public static Optional<TypeHandler> findHandler(String fqcn) {
        return handlers.stream().filter(h -> h.supportsType(fqcn)).findFirst();
    }
}
