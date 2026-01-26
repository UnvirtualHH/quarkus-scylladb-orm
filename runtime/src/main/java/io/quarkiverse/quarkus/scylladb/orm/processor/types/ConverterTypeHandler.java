package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import java.util.List;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
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
 *
 * The CQL column type is extracted from the AttributeConverter's second generic parameter.
 */
public class ConverterTypeHandler implements TypeHandler {

    private static final String ATTRIBUTE_CONVERTER_FQN = "io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter";

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

        // Extract the CQL type from AttributeConverter<EntityType, CqlType>
        ClassName cqlTypeClass = extractCqlType(converterType);

        return CodeBlock.of(
                """
                        $T $L = new $T();
                        if ($L.get($S, $T.class) != null) \
                        $L.$L($L.toEntityAttribute($L.get($S, $T.class)));
                        """,
                converterClass, converterVar, converterClass,
                rowVar, columnName, cqlTypeClass,
                targetVar, resolveSetterName(field),
                converterVar, rowVar, columnName, cqlTypeClass);
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

    /**
     * Extracts the CQL type (second generic parameter) from AttributeConverter<EntityType, CqlType>.
     * Falls back to Object if the type cannot be determined.
     */
    private ClassName extractCqlType(TypeMirror converterType) {
        if (!(converterType instanceof DeclaredType declaredType)) {
            return ClassName.get(Object.class);
        }

        TypeElement typeElement = (TypeElement) declaredType.asElement();

        // Look through all interfaces implemented by the converter
        for (TypeMirror iface : typeElement.getInterfaces()) {
            if (!(iface instanceof DeclaredType ifaceDeclared)) {
                continue;
            }

            String ifaceName = ((TypeElement) ifaceDeclared.asElement()).getQualifiedName().toString();
            if (ATTRIBUTE_CONVERTER_FQN.equals(ifaceName)) {
                List<? extends TypeMirror> typeArgs = ifaceDeclared.getTypeArguments();
                if (typeArgs.size() >= 2) {
                    TypeMirror cqlType = typeArgs.get(1);
                    return ClassName.bestGuess(cqlType.toString());
                }
            }
        }

        // Fallback to Object if we can't determine the type
        return ClassName.get(Object.class);
    }
}