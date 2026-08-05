package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import com.google.auto.service.AutoService;

import io.quarkiverse.quarkus.scylladb.orm.mapping.GenerateRepository;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Table;

/**
 * Annotation processor for Scylla/Cassandra entities.
 * <p>
 * For an entity {@code Foo} it generates:
 * <ul>
 * <li>{@code FooMapper}</li>
 * <li>{@code FooBaseRepository} (blocking)</li>
 * <li>{@code FooBaseReactiveRepository} (reactive)</li>
 * </ul>
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("io.quarkiverse.quarkus.scylladb.orm.mapping.Table")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class EntityMapperProcessor extends AbstractProcessor {

    private final MapperGenerator mapperGenerator = new MapperGenerator();
    private final RepositoryGenerator repositoryGenerator = new RepositoryGenerator();
    private final ReactiveRepositoryGenerator reactiveRepositoryGenerator = new ReactiveRepositoryGenerator();

    private final Set<String> generatedClasses = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Table.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                continue;
            }

            TypeElement entityType = (TypeElement) element;
            String packageName = processingEnv.getElementUtils()
                    .getPackageOf(entityType)
                    .getQualifiedName()
                    .toString();
            String entityName = entityType.getSimpleName().toString();

            Table tableAnn = entityType.getAnnotation(Table.class);
            String tableName = tableAnn.value();
            String keyspace = tableAnn.keyspace();

            // Mapper
            String mapperClassName = entityName + "Mapper";
            String mapperFQN = packageName + "." + mapperClassName;

            if (generatedClasses.add(mapperFQN)) {
                mapperGenerator.generateMapper(packageName, entityType, mapperClassName, processingEnv);
            }

            // Repositories
            GenerateRepository genRepo = entityType.getAnnotation(GenerateRepository.class);
            GenerateRepository.RepositoryType repoType = (genRepo == null)
                    ? GenerateRepository.RepositoryType.BOTH
                    : genRepo.value();

            if (repoType == GenerateRepository.RepositoryType.BLOCKING
                    || repoType == GenerateRepository.RepositoryType.BOTH) {
                String repoClassName = entityName + "BaseRepository";
                String repoFQN = packageName + "." + repoClassName;
                if (generatedClasses.add(repoFQN)) {
                    repositoryGenerator.generateRepository(
                            packageName,
                            entityType,
                            repoClassName,
                            mapperClassName,
                            keyspace,
                            tableName,
                            processingEnv);
                }
            }

            if (repoType == GenerateRepository.RepositoryType.REACTIVE
                    || repoType == GenerateRepository.RepositoryType.BOTH) {
                String reactiveClassName = entityName + "BaseReactiveRepository";
                String reactiveFQN = packageName + "." + reactiveClassName;
                if (generatedClasses.add(reactiveFQN)) {
                    reactiveRepositoryGenerator.generateReactiveRepository(
                            packageName,
                            entityType,
                            reactiveClassName,
                            mapperClassName,
                            keyspace,
                            tableName,
                            processingEnv);
                }
            }
        }
        return true;
    }
}
