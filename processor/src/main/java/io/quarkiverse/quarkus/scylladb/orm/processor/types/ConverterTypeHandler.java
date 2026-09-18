package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil;

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
     * also share the constant — and from its <em>fully qualified</em> name, so two
     * converters that merely share a simple name do not.
     */
    private static String converterFieldName(TypeMirror converterType) {
        return constantNameFor(converterType.toString());
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
     * Extracts the CQL type (second type argument) from
     * {@code AttributeConverter<EntityType, CqlType>}.
     * <p>
     * Walks the whole supertype chain, not just the converter's own interface list: a
     * converter that inherits the interface from an abstract base class used to fall
     * through to the {@code Object} fallback, and the mapper then emitted
     * {@code row.get(column, Object.class)} — which fails at runtime with
     * {@code CodecNotFoundException}, far from the converter that caused it.
     *
     * @throws IllegalArgumentException if the type argument cannot be determined, so the
     *         problem is reported against the entity at build time instead
     */
    private ClassName extractCqlType(TypeMirror converterType) {
        ClassName cqlType = findCqlType(converterType, new HashSet<>());
        if (cqlType != null) {
            return cqlType;
        }
        throw new IllegalArgumentException("@Convert converter " + converterType
                + " does not resolve to a concrete " + ATTRIBUTE_CONVERTER_FQN
                + "<EntityType, CqlType>. Implement the interface with both type arguments spelled out "
                + "(a raw or still-generic converter gives the mapper no column type to read).");
    }

    /** Depth-first search over superclasses and interfaces, guarding against cycles. */
    private ClassName findCqlType(TypeMirror type, Set<String> visited) {
        if (!(type instanceof DeclaredType declaredType)) {
            return null;
        }
        TypeElement typeElement = (TypeElement) declaredType.asElement();
        if (!visited.add(typeElement.getQualifiedName().toString())) {
            return null;
        }

        if (ATTRIBUTE_CONVERTER_FQN.equals(typeElement.getQualifiedName().toString())) {
            List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
            if (typeArgs.size() >= 2 && MapperUtil.isRawClass(typeArgs.get(1))) {
                return ClassName.bestGuess(typeArgs.get(1).toString());
            }
            return null;
        }

        for (TypeMirror iface : typeElement.getInterfaces()) {
            ClassName found = findCqlType(iface, visited);
            if (found != null) {
                return found;
            }
        }
        return findCqlType(typeElement.getSuperclass(), visited);
    }
}
