package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import java.util.List;
import java.util.Locale;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;

import io.quarkiverse.quarkus.scylladb.orm.enums.EnumType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Enumerated;
import io.quarkiverse.quarkus.scylladb.orm.processor.TypeHandler;

public class EnumTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return field.getAnnotation(Enumerated.class) != null;
    }

    /**
     * {@code Enum.values()} clones its backing array on every call, so ORDINAL mapping
     * would allocate once per field per row. Cache the array instead.
     */
    @Override
    public List<FieldSpec> generateSharedFields(VariableElement field) {
        if (field.getAnnotation(Enumerated.class).value() != EnumType.ORDINAL) {
            return List.of();
        }
        ClassName enumType = ClassName.bestGuess(field.asType().toString());
        return List.of(FieldSpec
                .builder(ArrayTypeName.of(enumType), valuesFieldName(field),
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.values()", enumType)
                .build());
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String rowVar, String columnName) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        ClassName enumType = ClassName.bestGuess(field.asType().toString());

        if (enumerated.value() == EnumType.ORDINAL) {
            String valuesField = valuesFieldName(field);
            String ordinalVar = field.getSimpleName() + "Ordinal";
            // Read the column once, and reject an out-of-range ordinal with a message
            // naming the column rather than a bare ArrayIndexOutOfBoundsException.
            return CodeBlock.builder()
                    .addStatement("Integer $L = $L.get($S, Integer.class)", ordinalVar, rowVar, columnName)
                    .beginControlFlow("if ($L != null)", ordinalVar)
                    .beginControlFlow("if ($L < 0 || $L >= $L.length)", ordinalVar, ordinalVar, valuesField)
                    .addStatement("throw new $T($S + $L + $S)",
                            IllegalStateException.class,
                            "Ordinal ", ordinalVar,
                            " in column '" + columnName + "' is out of range for enum " + field.asType())
                    .endControlFlow()
                    .addStatement("$L.$L($L[$L])", targetVar, resolveSetterName(field), valuesField, ordinalVar)
                    .endControlFlow()
                    .build();
        }

        String nameVar = field.getSimpleName() + "Name";
        return CodeBlock.builder()
                .addStatement("String $L = $L.get($S, String.class)", nameVar, rowVar, columnName)
                .beginControlFlow("if ($L != null)", nameVar)
                .addStatement("$L.$L($T.valueOf($L))", targetVar, resolveSetterName(field), enumType, nameVar)
                .endControlFlow()
                .build();
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar, String columnName) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        String getter = resolveGetterName(field);
        String valueVar = field.getSimpleName() + "Enum";
        String conversion = enumerated.value() == EnumType.ORDINAL ? "ordinal()" : "name()";

        return CodeBlock.builder()
                .addStatement("var $L = $L.$L()", valueVar, entityVar, getter)
                .beginControlFlow("if ($L != null)", valueVar)
                .addStatement("$L.put($S, $L.$L)", mapVar, columnName, valueVar, conversion)
                .endControlFlow()
                .build();
    }

    /** Derived from the enum type so two fields of the same enum share the constant. */
    private static String valuesFieldName(VariableElement field) {
        String simpleName = field.asType().toString();
        int lastDot = simpleName.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = simpleName.substring(lastDot + 1);
        }
        return simpleName.replace('.', '_').toUpperCase(Locale.ROOT) + "_VALUES";
    }
}
