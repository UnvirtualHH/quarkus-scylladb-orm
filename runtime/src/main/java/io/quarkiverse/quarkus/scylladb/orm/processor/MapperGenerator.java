package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
import io.quarkiverse.quarkus.scylladb.orm.processor.util.EntityFields;
import io.quarkiverse.quarkus.scylladb.orm.processor.util.EntityFields.KeyField;

public class MapperGenerator {

    public void generateMapper(String packageName,
            TypeElement entityType,
            String mapperClassName,
            ProcessingEnvironment processingEnv) {
        MethodSpec mapMethod = generateMapMethod(entityType, processingEnv);
        MethodSpec toPropertiesMethod = generateToPropertiesMethod(entityType, processingEnv);
        MethodSpec getEntityTypeMethod = generateGetEntityTypeMethod(entityType);
        MethodSpec registerSelfMethod = generateRegisterSelfMethod(entityType);

        MethodSpec getPkComponents = generateGetKeyComponents(entityType, true, processingEnv);
        MethodSpec getCkComponents = generateGetKeyComponents(entityType, false, processingEnv);

        MethodSpec getPkNamesArray = generateGetPartitionKeyNamesArray(entityType, processingEnv);
        MethodSpec getCkNamesArray = generateGetClusteringKeyNamesArray(entityType, processingEnv);
        MethodSpec getColumnNamesArray = generateGetColumnNamesArray();

        // Static constant fields for key name arrays
        FieldSpec pkNamesField = generateKeyNamesConstant("PK_NAMES",
                EntityFields.partitionKeyFields(entityType, processingEnv));
        FieldSpec ckNamesField = generateKeyNamesConstant("CK_NAMES",
                EntityFields.clusteringKeyFields(entityType, processingEnv));
        FieldSpec columnNamesField = generateColumnNamesConstant(entityType, processingEnv);

        TypeSpec.Builder mapperClass = TypeSpec.classBuilder(mapperClassName)
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
                .addField(pkNamesField)
                .addField(ckNamesField)
                .addField(columnNamesField)
                .addMethod(mapMethod)
                .addMethod(toPropertiesMethod)
                .addMethod(getColumnNamesArray)
                .addMethod(getPkNamesArray)
                .addMethod(getCkNamesArray)
                .addMethod(getPkComponents)
                .addMethod(getCkComponents)
                .addMethod(getEntityTypeMethod)
                .addMethod(registerSelfMethod);

        // Constants contributed by type handlers (converter instances, cached enum
        // values), so their generated code allocates once per mapper rather than once
        // per field per row. Deduplicated by name: handlers derive the name from the
        // converter/enum type, so fields sharing one also share the constant.
        Map<String, FieldSpec> sharedFields = new LinkedHashMap<>();
        for (VariableElement field : EntityFields.mappedFields(entityType, processingEnv)) {
            TypeHandlerRegistry.findHandler(field, processingEnv.getTypeUtils(), processingEnv.getElementUtils())
                    .ifPresent(handler -> handler.generateSharedFields(field)
                            .forEach(spec -> sharedFields.putIfAbsent(spec.name(), spec)));
        }
        sharedFields.values().forEach(mapperClass::addField);

        TypeSpec mapperClassSpec = mapperClass.build();

        JavaFile javaFile = JavaFile.builder(packageName, mapperClassSpec).build();

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

        for (VariableElement field : EntityFields.allFields(entityType, env)) {
            if (field.getAnnotation(Transient.class) != null)
                continue;

            String colName = resolveColumnName(field);
            String typeString = field.asType().toString();
            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field,
                    env.getTypeUtils(), env.getElementUtils());

            if (handler.isPresent()) {
                builder.addCode(handler.get().generateSetterCode(field, "instance", "row", colName));
                continue;
            }

            // --- Special handling for generic collection types ---
            // Uses typed Row accessors (getList/getSet/getMap) instead of GenericType
            // to avoid reflection at runtime (required for native image builds)
            if (typeString.startsWith("java.util.Set")
                    || typeString.startsWith("java.util.List")
                    || typeString.startsWith("java.util.Map")) {

                String varName = field.getSimpleName().toString() + "Val";
                var typeArgs = ((DeclaredType) field.asType()).getTypeArguments();

                if (typeString.startsWith("java.util.List")) {
                    builder.addStatement("var $L = row.getList($S, $T.class)",
                            varName, colName, TypeName.get(typeArgs.get(0)));
                } else if (typeString.startsWith("java.util.Set")) {
                    builder.addStatement("var $L = row.getSet($S, $T.class)",
                            varName, colName, TypeName.get(typeArgs.get(0)));
                } else {
                    builder.addStatement("var $L = row.getMap($S, $T.class, $T.class)",
                            varName, colName,
                            TypeName.get(typeArgs.get(0)),
                            TypeName.get(typeArgs.get(1)));
                }

                builder.beginControlFlow("if ($L != null)", varName)
                        .addStatement("instance.$L($L)", setterName(field), varName)
                        .endControlFlow();
            } else {
                String varName = field.getSimpleName().toString() + "Val";
                builder.addStatement("var $L = row.get($S, $T.class)",
                        varName, colName, TypeName.get(field.asType()))
                        .beginControlFlow("if ($L != null)", varName)
                        .addStatement("instance.$L($L)", setterName(field), varName)
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
                        Map.class, java.util.LinkedHashMap.class);

        for (VariableElement field : EntityFields.allFields(entityType, env)) {
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
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                .addStatement("registry.registerSelf($T.class, this)", TypeName.get(entityType.asType()))
                .build();
    }

    private MethodSpec generateGetKeyComponents(TypeElement entityType, boolean partition, ProcessingEnvironment env) {
        var keys = partition ? EntityFields.partitionKeyFields(entityType, env)
                : EntityFields.clusteringKeyFields(entityType, env);

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

        // Keys are already sorted by ordinal at compile time via partitionKeyFields/clusteringKeyFields
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

        b.addStatement("return list");
        return b.build();
    }

    private FieldSpec generateKeyNamesConstant(String fieldName, List<KeyField> keys) {
        if (keys.isEmpty()) {
            return FieldSpec.builder(String[].class, fieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new String[0]")
                    .build();
        }
        String literal = keys.stream()
                .map(k -> "\"" + resolveColumnName(k.field()) + "\"")
                .collect(Collectors.joining(", "));
        return FieldSpec.builder(String[].class, fieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("{$L}", literal)
                .build();
    }

    /**
     * Every column {@code map(Row)} reads, in the same order. Repositories join these
     * into an explicit projection so that reads never issue a wildcard select.
     */
    private FieldSpec generateColumnNamesConstant(TypeElement entityType, ProcessingEnvironment env) {
        String literal = EntityFields.mappedFields(entityType, env).stream()
                .map(f -> "\"" + resolveColumnName(f) + "\"")
                .collect(Collectors.joining(", "));
        return FieldSpec.builder(String[].class, "COLUMN_NAMES", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(literal.isEmpty() ? "new String[0]" : "{" + literal + "}")
                .build();
    }

    private MethodSpec generateGetColumnNamesArray() {
        return MethodSpec.methodBuilder("getColumnNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class)
                .addStatement("return COLUMN_NAMES.clone()")
                .build();
    }

    private MethodSpec generateGetPartitionKeyNamesArray(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("getPartitionKeyNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class)
                .addStatement("return PK_NAMES.clone()");
        return b.build();
    }

    private MethodSpec generateGetClusteringKeyNamesArray(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("getClusteringKeyNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class)
                .addStatement("return CK_NAMES.clone()");
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

}