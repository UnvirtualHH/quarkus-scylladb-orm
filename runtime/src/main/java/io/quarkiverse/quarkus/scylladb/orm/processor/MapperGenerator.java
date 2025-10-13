package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

public class MapperGenerator {

    public void generateMapper(String packageName,
            TypeElement entityType,
            String mapperClassName,
            ProcessingEnvironment processingEnv) {
        TypeHandlerRegistry.init(processingEnv);

        MethodSpec mapMethod = generateMapMethod(entityType, processingEnv);
        MethodSpec toPropertiesMethod = generateToPropertiesMethod(entityType, processingEnv);
        MethodSpec getEntityTypeMethod = generateGetEntityTypeMethod(entityType);
        MethodSpec registerSelfMethod = generateRegisterSelfMethod(entityType);

        MethodSpec getPkComponents = generateGetKeyComponents(entityType, true);
        MethodSpec getCkComponents = generateGetKeyComponents(entityType, false);

        MethodSpec getPkNamesArray = generateGetPartitionKeyNamesArray(entityType);
        MethodSpec getCkNamesArray = generateGetClusteringKeyNamesArray(entityType);

        TypeSpec mapperClass = TypeSpec.classBuilder(mapperClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ApplicationScoped.class)
                .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.scylladb.orm.mapping", "EntityMapper"),
                        TypeName.get(entityType.asType())))
                .addField(FieldSpec.builder(
                        ClassName.get("io.quarkiverse.quarkus.scylladb.orm.mapping", "EntityMapperRegistry"),
                        "registry",
                        Modifier.PRIVATE)
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .build())
                .addMethod(mapMethod)
                .addMethod(toPropertiesMethod)
                .addMethod(getPkNamesArray)
                .addMethod(getCkNamesArray)
                .addMethod(getPkComponents)
                .addMethod(getCkComponents)
                .addMethod(getEntityTypeMethod)
                .addMethod(registerSelfMethod)
                .build();

        JavaFile javaFile = JavaFile.builder(packageName, mapperClass).build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated Scylla mapper: " + packageName + "." + mapperClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.toString());
        }
    }

    private MethodSpec generateMapMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("map")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ClassName.get("com.datastax.oss.driver.api.core.cql", "Row"), "row")
                .addStatement("$T instance = new $T()",
                        TypeName.get(entityType.asType()), TypeName.get(entityType.asType()));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(Transient.class) != null)
                continue;

            String colName = resolveColumnName(field);
            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field,
                    env.getTypeUtils(), env.getElementUtils());

            if (handler.isPresent()) {
                builder.addCode(handler.get().generateSetterCode(field, "instance", "row", colName));
            } else {
                builder.beginControlFlow("if (row.get($S, $T.class) != null)", colName, TypeName.get(field.asType()))
                        .addStatement("instance.$L(row.get($S, $T.class))",
                                setterName(field), colName, TypeName.get(field.asType()))
                        .endControlFlow();
            }
        }

        builder.addStatement("return instance");
        return builder.build();
    }

    private MethodSpec generateToPropertiesMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("toProperties")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(Map.class, String.class, Object.class))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T<String,Object> props = new $T<>()",
                        Map.class, java.util.HashMap.class);

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(Transient.class) != null)
                continue;

            String colName = resolveColumnName(field);
            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field,
                    env.getTypeUtils(), env.getElementUtils());

            if (field.getAnnotation(GeneratedValue.class) != null
                    && field.asType().toString().equals("java.util.UUID")) {
                String getter = getterName(field);
                builder.beginControlFlow("if (entity.$L() == null)", getter)
                        .addStatement("entity.$L($T.randomUUID())", setterName(field), UUID.class)
                        .endControlFlow();
            }

            if (handler.isPresent()) {
                builder.addCode(handler.get().generateToDbCode(field, "entity", "props", colName));
            } else {
                String getter = getterName(field);
                String type = field.asType().toString();
                if (isPrimitive(type)) {
                    builder.addStatement("props.put($S, entity.$L())", colName, getter);
                } else {
                    builder.beginControlFlow("if (entity.$L() != null)", getter)
                            .addStatement("props.put($S, entity.$L())", colName, getter)
                            .endControlFlow();
                }
            }
        }

        builder.addStatement("return props");
        return builder.build();
    }

    private MethodSpec generateGetEntityTypeMethod(TypeElement entityType) {
        return MethodSpec.methodBuilder("getEntityType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), TypeName.get(entityType.asType())))
                .addStatement("return $T.class", TypeName.get(entityType.asType()))
                .build();
    }

    private MethodSpec generateRegisterSelfMethod(TypeElement entityType) {
        return MethodSpec.methodBuilder("registerSelf")
                .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                .addStatement("registry.registerSelf($T.class, this)", TypeName.get(entityType.asType()))
                .build();
    }

    private MethodSpec generateGetKeyComponents(TypeElement entityType, boolean partition) {
        var keys = partition ? partitionKeyFields(entityType) : clusteringKeyFields(entityType);

        var listType = ParameterizedTypeName.get(
                ClassName.get(List.class),
                ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.scylladb.orm.mapping", "KeyComponent"),
                        WildcardTypeName.subtypeOf(Object.class)));

        MethodSpec.Builder b = MethodSpec.methodBuilder(
                partition ? "getPartitionKeyComponents" : "getClusteringKeyComponents")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(listType)
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T list = new $T<>($L)",
                        listType,
                        ClassName.get(ArrayList.class),
                        keys.size());

        if (!keys.isEmpty()) {
            for (KeyField key : keys) {
                var f = key.field();
                String colName = resolveColumnName(f);
                TypeName boxed = TypeName.get(f.asType()).box();
                b.addStatement("list.add($T.of($S, $T.of($T.class), entity.$L(), $L))",
                        ClassName.get("io.quarkiverse.quarkus.scylladb.orm.mapping", "KeyComponent"),
                        colName,
                        ClassName.get("com.datastax.oss.driver.api.core.type.reflect", "GenericType"),
                        boxed,
                        getterName(f),
                        key.ordinal());
            }
            b.addStatement("$T.sort(list, $T.comparingInt($T::ordinal))",
                    ClassName.get(java.util.Collections.class),
                    ClassName.get(Comparator.class),
                    ClassName.get("io.quarkiverse.quarkus.scylladb.orm.mapping", "KeyComponent"));
        }

        b.addStatement("return list");
        return b.build();
    }

    private MethodSpec generateGetPartitionKeyNamesArray(TypeElement entityType) {
        var pks = partitionKeyFields(entityType);
        MethodSpec.Builder b = MethodSpec.methodBuilder("getPartitionKeyNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class);

        if (pks.isEmpty()) {
            b.addStatement("return new String[0]");
        } else {
            b.addStatement("String[] names = new String[$L]", pks.size());
            for (int i = 0; i < pks.size(); i++) {
                var f = pks.get(i).field();
                b.addStatement("names[$L] = $S", i, resolveColumnName(f));
            }
            b.addStatement("return names");
        }
        return b.build();
    }

    private MethodSpec generateGetClusteringKeyNamesArray(TypeElement entityType) {
        var cks = clusteringKeyFields(entityType);
        MethodSpec.Builder b = MethodSpec.methodBuilder("getClusteringKeyNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class);

        if (cks.isEmpty()) {
            b.addStatement("return new String[0]");
        } else {
            b.addStatement("String[] names = new String[$L]", cks.size());
            for (int i = 0; i < cks.size(); i++) {
                var f = cks.get(i).field();
                b.addStatement("names[$L] = $S", i, resolveColumnName(f));
            }
            b.addStatement("return names");
        }
        return b.build();
    }

    // === Helper ===

    private static String resolveColumnName(VariableElement field) {
        Column colAnn = field.getAnnotation(Column.class);
        if (colAnn != null && !colAnn.value().isEmpty()) {
            return colAnn.value();
        }
        return field.getSimpleName().toString();
    }

    private static String getterName(VariableElement field) {
        String name = capitalize(field.getSimpleName().toString());
        String type = field.asType().toString();
        if (type.equals("boolean")) {
            return "is" + name;
        }
        return "get" + name;
    }

    private static String setterName(VariableElement field) {
        return "set" + capitalize(field.getSimpleName().toString());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "boolean", "byte", "short", "int", "long", "float", "double", "char" -> true;
            default -> false;
        };
    }

    private record KeyField(VariableElement field, int ordinal) {
    }

    private static List<KeyField> partitionKeyFields(TypeElement entityType) {
        return ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .filter(f -> f.getAnnotation(PartitionKey.class) != null)
                .map(f -> new KeyField(f, f.getAnnotation(PartitionKey.class).ordinal()))
                .sorted(java.util.Comparator.comparingInt(KeyField::ordinal))
                .toList();
    }

    private static List<KeyField> clusteringKeyFields(TypeElement entityType) {
        return ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .filter(f -> f.getAnnotation(ClusteringKey.class) != null)
                .map(f -> new KeyField(f, f.getAnnotation(ClusteringKey.class).ordinal()))
                .sorted(java.util.Comparator.comparingInt(KeyField::ordinal))
                .toList();
    }
}
