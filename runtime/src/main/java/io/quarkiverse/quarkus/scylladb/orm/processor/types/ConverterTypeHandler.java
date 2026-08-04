package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import java.util.List;
import java.util.Locale;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;

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

    /**
     * Converters are stateless, so one instance per mapper is enough. Allocating one per
     * field per row — which is what inlining {@code new Converter()} into map() and
     * toProperties() does — is pure garbage on the hottest path.
     */
    @Override
    public List<FieldSpec> generateSharedFields(VariableElement field) {
        TypeMirror converterType = getConverterType(field);
        ClassName converterClass = ClassName.bestGuess(converterType.toString());
        return List.of(FieldSpec
                .builder(converterClass, converterFieldName(converterType),
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", converterClass)
                .build());
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field,
            String targetVar,
            String rowVar,
            String columnName) {
        TypeMirror converterType = getConverterType(field);
        ClassName cqlTypeClass = extractCqlType(converterType);
        String valueVar = field.getSimpleName() + "Raw";

        // Read the column once — the previous version called row.get twice per field per
        // row, once for the null check and once for the value.
        return CodeBlock.builder()
                .addStatement("$T $L = $L.get($S, $T.class)", cqlTypeClass, valueVar, rowVar, columnName, cqlTypeClass)
                .beginControlFlow("if ($L != null)", valueVar)
                .addStatement("$L.$L($L.toEntityAttribute($L))",
                        targetVar, resolveSetterName(field), converterFieldName(converterType), valueVar)
                .endControlFlow()
                .build();
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field,
            String entityVar,
            String mapVar,
            String columnName) {
        TypeMirror converterType = getConverterType(field);
        String getter = resolveGetterName(field);
        String valueVar = field.getSimpleName() + "Attr";

        return CodeBlock.builder()
                .addStatement("var $L = $L.$L()", valueVar, entityVar, getter)
                .beginControlFlow("if ($L != null)", valueVar)
                .addStatement("$L.put($S, $L.toCqlColumn($L))",
                        mapVar, columnName, converterFieldName(converterType), valueVar)
                .endControlFlow()
                .build();
    }

    /**
     * Derived from the converter type, not the field, so two fields sharing a converter
     * also share the constant.
     */
    private static String converterFieldName(TypeMirror converterType) {
        String simpleName = converterType.toString();
        int lastDot = simpleName.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = simpleName.substring(lastDot + 1);
        }
        return simpleName.replace('.', '_').toUpperCase(Locale.ROOT);
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
