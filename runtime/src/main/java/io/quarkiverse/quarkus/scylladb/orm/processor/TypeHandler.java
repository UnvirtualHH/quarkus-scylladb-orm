package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.List;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;

public interface TypeHandler {

    default boolean supports(VariableElement field, Types types, Elements elements) {
        return false;
    }

    default boolean supportsType(String fqcn) {
        return false;
    }

    CodeBlock generateSetterCode(VariableElement field, String targetVar, String rowVar, String columnName);

    CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar, String columnName);

    default CodeBlock generateParameterConversion(String paramName) {
        return CodeBlock.of("$L", paramName);
    }

    /**
     * Constants this handler's generated code refers to, hoisted into the mapper class.
     * <p>
     * Lets a handler allocate a helper once per mapper instead of once per field per row.
     * The generator deduplicates by field name, so a handler may return the same constant
     * for several fields.
     *
     * @param field the field being generated for
     * @return fields to add to the mapper class; empty by default
     */
    default List<FieldSpec> generateSharedFields(VariableElement field) {
        return List.of();
    }
}
