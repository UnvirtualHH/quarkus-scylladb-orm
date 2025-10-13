package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Convert;
import io.quarkiverse.quarkus.scylladb.orm.processor.TypeHandler;

/**
 * Handles fields annotated with @Convert(...)
 * Generates converter.toEntityAttribute(...) / converter.toCqlColumn(...)
 * code for mapper methods.
 */
public class ConverterTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return field.getAnnotation(Convert.class) != null;
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field,
            String targetVar,
            String rowVar,
            String columnName) {
        TypeMirror converterType = getConverterType(field);
        ClassName converterClass = ClassName.bestGuess(converterType.toString());
        String converterVar = field.getSimpleName() + "Converter";

        return CodeBlock.of(
                """
                        $T $L = new $T();
                        if ($L.get($S, String.class) != null) \
                        $L.$L($L.toEntityAttribute($L.get($S, String.class)));
                        """,
                converterClass, converterVar, converterClass,
                rowVar, columnName,
                targetVar, resolveSetterName(field),
                converterVar, rowVar, columnName);
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field,
            String entityVar,
            String mapVar,
            String columnName) {
        TypeMirror converterType = getConverterType(field);
        ClassName converterClass = ClassName.bestGuess(converterType.toString());
        String converterVar = field.getSimpleName() + "Converter";
        String getter = resolveGetterName(field);

        return CodeBlock.of(
                """
                        $T $L = new $T();
                        if ($L.$L() != null) \
                        $L.put($S, $L.toCqlColumn($L.$L()));
                        """,
                converterClass, converterVar, converterClass,
                entityVar, getter,
                mapVar, columnName,
                converterVar, entityVar, getter);
    }

    private TypeMirror getConverterType(VariableElement field) {
        try {
            field.getAnnotation(Convert.class).value(); // will throw
            throw new IllegalStateException("Expected MirroredTypeException");
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
    }
}