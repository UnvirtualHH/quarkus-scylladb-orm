package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.io.IOException;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.scylladb.orm.processor.util.EntityFields;

public class ReactiveRepositoryGenerator {

    public void generateReactiveRepository(
            String packageName,
            TypeElement entityType,
            String repositoryClassName,
            String mapperClassName,
            String keyspace,
            String table,
            ProcessingEnvironment processingEnv) {

        String fullClassName = packageName + "." + repositoryClassName;
        String tableName = (keyspace != null && !keyspace.isEmpty()) ? keyspace + "." + table : table;

        // Protected, not public: it exists only so CDI can build a client proxy, and
        // an instance created through it has no session and would NPE on every call.
        MethodSpec noArgsConstructor = MethodSpec.constructorBuilder()
                .addStatement("super()")
                .addModifiers(Modifier.PROTECTED)
                .build();

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("com.datastax.oss.driver.api.core", "CqlSession"), "session")
                .addParameter(ClassName.get(packageName, mapperClassName), "mapper")
                .addParameter(
                        ClassName.get("io.quarkiverse.quarkus.scylladb.orm.repository", "ReactiveRepositoryRegistry"),
                        "registry")
                .addStatement("super(session, $S, mapper, registry)", tableName)
                .build();

        MethodSpec registerSelf = MethodSpec.methodBuilder("registerSelf")
                .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                .addModifiers(Modifier.PUBLIC)
                .addStatement("registry.register($T.class, this)", TypeName.get(entityType.asType()))
                .build();

        MethodSpec getEntityType = MethodSpec.methodBuilder("getEntityType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), TypeName.get(entityType.asType())))
                .addStatement("return $T.class", TypeName.get(entityType.asType()))
                .build();

        TypeSpec.Builder repositoryClassBuilder = TypeSpec.classBuilder(repositoryClassName)
                .addAnnotation(ApplicationScoped.class)
                .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                .addModifiers(Modifier.PUBLIC)
                .addMethod(noArgsConstructor)
                .addMethod(constructor)
                .addMethod(registerSelf)
                .addMethod(getEntityType)
                .superclass(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.scylladb.orm.repository", "ReactiveRepository"),
                        TypeName.get(entityType.asType()),
                        // The partition key's own type where there is exactly one, so
                        // findById/deleteById/existsById are type-safe instead of
                        // accepting any Object.
                        EntityFields.idType(entityType, processingEnv)));

        List<MethodSpec> generatedMethods = QueryMethodFactory.buildQueryMethods(entityType, true, processingEnv);
        generatedMethods.forEach(repositoryClassBuilder::addMethod);

        TypeSpec repositoryClass = repositoryClassBuilder.build();

        JavaFile javaFile = JavaFile.builder(packageName, repositoryClass).build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated Scylla reactive repository: " + fullClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate Scylla reactive repository: " + e.getMessage());
        }
    }
}
