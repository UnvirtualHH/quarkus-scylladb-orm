package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final ClassName ROW_CLASS = ClassName.get("com.datastax.oss.driver.api.core.cql", "Row");
    private static final ClassName GENERIC_TYPE_CLASS = ClassName.get("com.datastax.oss.driver.api.core.type.reflect",
            "GenericType");
    private static final ClassName KEY_COMPONENT_CLASS = ClassName.get(
            "io.quarkiverse.quarkus.scylladb.orm.mapping", "KeyComponent");
    private static final ClassName ENTITY_MAPPER_CLASS = ClassName.get(
            "io.quarkiverse.quarkus.scylladb.orm.mapping", "EntityMapper");
    private static final ClassName ENTITY_MAPPER_REGISTRY_CLASS = ClassName.get(
            "io.quarkiverse.quarkus.scylladb.orm.mapping", "EntityMapperRegistry");

    public void generateMapper(String packageName,
            TypeElement entityType,
            String mapperClassName,
            ProcessingEnvironment processingEnv) {

        // Validate inputs
        Objects.requireNonNull(packageName, "Package name must not be null");
        Objects.requireNonNull(entityType, "Entity type must not be null");
        Objects.requireNonNull(mapperClassName, "Mapper class name must not be null");
        Objects.requireNonNull(processingEnv, "ProcessingEnvironment must not be null");

        if (packageName.isBlank()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Package name must not be blank", entityType);
            return;
        }

        if (mapperClassName.isBlank()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Mapper class name must not be blank", entityType);
            return;
        }

        try {
            TypeHandlerRegistry.init(processingEnv);

            MethodSpec mapMethod = generateMapMethod(entityType, processingEnv);
            MethodSpec toPropertiesMethod = generateToPropertiesMethod(entityType, processingEnv);
            MethodSpec getEntityTypeMethod = generateGetEntityTypeMethod(entityType);
            MethodSpec registerSelfMethod = generateRegisterSelfMethod(entityType);

            MethodSpec getPkComponents = generateGetKeyComponents(entityType, true, processingEnv);
            MethodSpec getCkComponents = generateGetKeyComponents(entityType, false, processingEnv);

            MethodSpec getPkNamesArray = generateGetPartitionKeyNamesArray(entityType, processingEnv);
            MethodSpec getCkNamesArray = generateGetClusteringKeyNamesArray(entityType, processingEnv);

            TypeSpec mapperClass = TypeSpec.classBuilder(mapperClassName)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addAnnotation(ApplicationScoped.class)
                    .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                    .addSuperinterface(ParameterizedTypeName.get(
                            ENTITY_MAPPER_CLASS,
                            TypeName.get(entityType.asType())))
                    .addField(FieldSpec.builder(
                            ENTITY_MAPPER_REGISTRY_CLASS,
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

            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated Scylla mapper: " + packageName + "." + mapperClassName);

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write mapper file: " + e.getMessage(), entityType);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate mapper: " + e.getMessage(), entityType);
        }
    }

    private MethodSpec generateMapMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("map")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ROW_CLASS, "row");

        // Add null check for row parameter
        builder.beginControlFlow("if (row == null)")
                .addStatement("throw new $T($S)", IllegalArgumentException.class, "Row must not be null")
                .endControlFlow();

        builder.addStatement("$T instance = new $T()",
                TypeName.get(entityType.asType()), TypeName.get(entityType.asType()));

        List<VariableElement> fields = allFields(entityType, env);
        if (fields.isEmpty()) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Entity " + entityType.getSimpleName() + " has no fields", entityType);
        }

        for (VariableElement field : fields) {
            if (field.getAnnotation(Transient.class) != null) {
                continue;
            }

            String colName = resolveColumnName(field);
            String typeString = field.asType().toString();
            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field,
                    env.getTypeUtils(), env.getElementUtils());

            if (handler.isPresent()) {
                builder.addCode(handler.get().generateSetterCode(field, "instance", "row", colName));
                continue;
            }

            // Special handling for generic collection types
            if (isGenericCollectionType(typeString)) {
                builder.addStatement("$T $LValue = row.get($S, new $T<$L>() {})",
                        TypeName.get(field.asType()),
                        field.getSimpleName(),
                        colName,
                        GENERIC_TYPE_CLASS,
                        typeString);

                builder.beginControlFlow("if ($LValue != null)", field.getSimpleName())
                        .addStatement("instance.$L($LValue)",
                                setterName(field),
                                field.getSimpleName())
                        .endControlFlow();
            } else {
                builder.addStatement("$T $LValue = row.get($S, $T.class)",
                        TypeName.get(field.asType()),
                        field.getSimpleName(),
                        colName,
                        TypeName.get(field.asType()));

                builder.beginControlFlow("if ($LValue != null)", field.getSimpleName())
                        .addStatement("instance.$L($LValue)",
                                setterName(field),
                                field.getSimpleName())
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
                .addParameter(TypeName.get(entityType.asType()), "entity");

        // Add null check for entity parameter
        builder.beginControlFlow("if (entity == null)")
                .addStatement("throw new $T($S)", IllegalArgumentException.class, "Entity must not be null")
                .endControlFlow();

        builder.addStatement("$T<String, Object> props = new $T<>()",
                Map.class, java.util.HashMap.class);

        for (VariableElement field : allFields(entityType, env)) {
            if (field.getAnnotation(Transient.class) != null) {
                continue;
            }

            String colName = resolveColumnName(field);
            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field,
                    env.getTypeUtils(), env.getElementUtils());

            // Handle @GeneratedValue for UUID fields
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
                    builder.addStatement("$T $LValue = entity.$L()",
                            TypeName.get(field.asType()),
                            field.getSimpleName(),
                            getter);
                    builder.beginControlFlow("if ($LValue != null)", field.getSimpleName())
                            .addStatement("props.put($S, $LValue)", colName, field.getSimpleName())
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

    private MethodSpec generateGetKeyComponents(TypeElement entityType, boolean partition, ProcessingEnvironment env) {
        List<KeyField> keys = partition
                ? partitionKeyFields(entityType, env)
                : clusteringKeyFields(entityType, env);

        ParameterizedTypeName listType = ParameterizedTypeName.get(
                ClassName.get(List.class),
                ParameterizedTypeName.get(
                        KEY_COMPONENT_CLASS,
                        WildcardTypeName.subtypeOf(Object.class)));

        String methodName = partition ? "getPartitionKeyComponents" : "getClusteringKeyComponents";

        MethodSpec.Builder b = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(listType)
                .addParameter(TypeName.get(entityType.asType()), "entity");

        // Add null check for entity parameter
        b.beginControlFlow("if (entity == null)")
                .addStatement("throw new $T($S)", IllegalArgumentException.class, "Entity must not be null")
                .endControlFlow();

        b.addStatement("$T list = new $T<>($L)",
                listType,
                ClassName.get(ArrayList.class),
                keys.size());

        if (!keys.isEmpty()) {
            for (KeyField key : keys) {
                VariableElement f = key.field();
                String colName = resolveColumnName(f);
                TypeName boxed = TypeName.get(f.asType()).box();
                String getter = getterName(f);

                // Store getter result in variable to avoid multiple calls
                b.addStatement("$T $LValue = entity.$L()",
                        boxed,
                        f.getSimpleName(),
                        getter);

                b.addStatement("list.add($T.of($S, $T.of($T.class), $LValue, $L))",
                        KEY_COMPONENT_CLASS,
                        colName,
                        GENERIC_TYPE_CLASS,
                        boxed,
                        f.getSimpleName(),
                        key.ordinal());
            }

            b.addStatement("list.sort($T.comparingInt($T::ordinal))",
                    ClassName.get(Comparator.class),
                    KEY_COMPONENT_CLASS);
        }

        b.addStatement("return list");
        return b.build();
    }

    private MethodSpec generateGetPartitionKeyNamesArray(TypeElement entityType, ProcessingEnvironment env) {
        List<KeyField> pks = partitionKeyFields(entityType, env);
        MethodSpec.Builder b = MethodSpec.methodBuilder("getPartitionKeyNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class);

        if (pks.isEmpty()) {
            b.addStatement("return new String[0]");
        } else {
            // Generate array inline for better performance
            String[] columnNames = pks.stream()
                    .map(kf -> "\"" + resolveColumnName(kf.field()) + "\"")
                    .toArray(String[]::new);

            b.addStatement("return new String[] { $L }", String.join(", ", columnNames));
        }
        return b.build();
    }

    private MethodSpec generateGetClusteringKeyNamesArray(TypeElement entityType, ProcessingEnvironment env) {
        List<KeyField> cks = clusteringKeyFields(entityType, env);
        MethodSpec.Builder b = MethodSpec.methodBuilder("getClusteringKeyNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class);

        if (cks.isEmpty()) {
            b.addStatement("return new String[0]");
        } else {
            // Generate array inline for better performance
            String[] columnNames = cks.stream()
                    .map(kf -> "\"" + resolveColumnName(kf.field()) + "\"")
                    .toArray(String[]::new);

            b.addStatement("return new String[] { $L }", String.join(", ", columnNames));
        }
        return b.build();
    }

    // === Helper Methods ===

    private static List<VariableElement> allFields(TypeElement type, ProcessingEnvironment env) {
        List<VariableElement> fields = new ArrayList<>();
        TypeElement currentType = type;

        while (currentType != null) {
            fields.addAll(ElementFilter.fieldsIn(currentType.getEnclosedElements()));

            var superType = currentType.getSuperclass();
            if (superType == null || superType.toString().equals("java.lang.Object")) {
                break;
            }

            var superElement = env.getTypeUtils().asElement(superType);
            if (!(superElement instanceof TypeElement)) {
                break;
            }

            currentType = (TypeElement) superElement;
        }

        return fields;
    }

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
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "boolean", "byte", "short", "int", "long", "float", "double", "char" -> true;
            default -> false;
        };
    }

    private static boolean isGenericCollectionType(String typeString) {
        return typeString.startsWith("java.util.Set<")
                || typeString.startsWith("java.util.List<")
                || typeString.startsWith("java.util.Map<");
    }

    private record KeyField(VariableElement field, int ordinal) {
    }

    private static List<KeyField> partitionKeyFields(TypeElement entityType, ProcessingEnvironment env) {
        return allFields(entityType, env).stream()
                .filter(f -> f.getAnnotation(PartitionKey.class) != null)
                .map(f -> new KeyField(f, f.getAnnotation(PartitionKey.class).ordinal()))
                .sorted(Comparator.comparingInt(KeyField::ordinal))
                .toList();
    }

    private static List<KeyField> clusteringKeyFields(TypeElement entityType, ProcessingEnvironment env) {
        return allFields(entityType, env).stream()
                .filter(f -> f.getAnnotation(ClusteringKey.class) != null)
                .map(f -> new KeyField(f, f.getAnnotation(ClusteringKey.class).ordinal()))
                .sorted(Comparator.comparingInt(KeyField::ordinal))
                .toList();
    }
}