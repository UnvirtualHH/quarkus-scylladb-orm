package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.scylladb.orm.mapping.EnumType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Enumerated;
import io.quarkiverse.quarkus.scylladb.orm.processor.TypeHandler;

public class EnumTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return field.getAnnotation(Enumerated.class) != null;
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String rowVar, String columnName) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        ClassName enumType = ClassName.bestGuess(field.asType().toString());

        if (enumerated.value() == EnumType.ORDINAL) {
            return CodeBlock.of(
                    "if ($L.get($S, Integer.class) != null) $L.$L($T.values()[$L.get($S, Integer.class)]);\n",
                    rowVar, columnName,
                    targetVar,
                    resolveSetterName(field),
                    enumType,
                    rowVar, columnName);
        } else {
            return CodeBlock.of(
                    "if ($L.get($S, String.class) != null) $L.$L($T.valueOf($L.get($S, String.class)));\n",
                    rowVar, columnName,
                    targetVar,
                    resolveSetterName(field),
                    enumType,
                    rowVar, columnName);
        }
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar, String columnName) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        String getter = resolveGetterName(field);

        if (enumerated.value() == EnumType.ORDINAL) {
            return CodeBlock.of(
                    "if ($L.$L() != null) $L.put($S, $L.$L().ordinal());\n",
                    entityVar, getter,
                    mapVar, columnName,
                    entityVar, getter);
        } else {
            return CodeBlock.of(
                    "if ($L.$L() != null) $L.put($S, $L.$L().name());\n",
                    entityVar, getter,
                    mapVar, columnName,
                    entityVar, getter);
        }
    }
}
