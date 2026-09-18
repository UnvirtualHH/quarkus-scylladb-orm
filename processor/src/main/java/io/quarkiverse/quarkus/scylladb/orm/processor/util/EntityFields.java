package io.quarkiverse.quarkus.scylladb.orm.processor.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import io.quarkiverse.quarkus.scylladb.orm.mapping.ClusteringKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Transient;

/**
 * Field inspection shared by the mapper and repository generators, so that the two
 * cannot disagree about what an entity's columns and keys are.
 */
public final class EntityFields {

    private EntityFields() {
    }

    /** A key field together with its declared ordinal. */
    public record KeyField(VariableElement field, int ordinal) {
    }

    /** All non-static fields, including inherited ones, in declaration order. */
    public static List<VariableElement> allFields(TypeElement type, ProcessingEnvironment env) {
        List<VariableElement> fields = new ArrayList<>();
        TypeElement current = type;
        while (current != null) {
            for (VariableElement field : ElementFilter.fieldsIn(current.getEnclosedElements())) {
                if (!field.getModifiers().contains(Modifier.STATIC)) {
                    fields.add(field);
                }
            }
            var superType = current.getSuperclass();
            if (superType == null || superType.toString().equals("java.lang.Object")) {
                break;
            }
            current = (TypeElement) env.getTypeUtils().asElement(superType);
        }
        return fields;
    }

    /** The fields that participate in mapping — everything except {@code @Transient}. */
    public static List<VariableElement> mappedFields(TypeElement type, ProcessingEnvironment env) {
        return allFields(type, env).stream()
                .filter(f -> f.getAnnotation(Transient.class) == null)
                .toList();
    }

    /** The column a field maps to — its {@code @Column} value, or the field name. */
    public static String resolveColumnName(VariableElement field) {
        Column column = field.getAnnotation(Column.class);
        return column != null && !column.value().isEmpty() ? column.value() : field.getSimpleName().toString();
    }

    /** The columns {@code map(Row)} reads, in declaration order. */
    public static List<String> mappedColumnNames(TypeElement type, ProcessingEnvironment env) {
        return mappedFields(type, env).stream().map(EntityFields::resolveColumnName).toList();
    }

    public static List<KeyField> partitionKeyFields(TypeElement type, ProcessingEnvironment env) {
        return allFields(type, env).stream()
                .filter(f -> f.getAnnotation(PartitionKey.class) != null)
                .map(f -> new KeyField(f, f.getAnnotation(PartitionKey.class).ordinal()))
                .sorted(Comparator.comparingInt(KeyField::ordinal))
                .toList();
    }

    public static List<KeyField> clusteringKeyFields(TypeElement type, ProcessingEnvironment env) {
        return allFields(type, env).stream()
                .filter(f -> f.getAnnotation(ClusteringKey.class) != null)
                .map(f -> new KeyField(f, f.getAnnotation(ClusteringKey.class).ordinal()))
                .sorted(Comparator.comparingInt(KeyField::ordinal))
                .toList();
    }

    /**
     * The type argument to use for a repository's {@code ID} parameter.
     * <p>
     * With exactly one partition key column this is that column's (boxed) type, which
     * makes {@code findById}/{@code deleteById}/{@code existsById} type-safe. With a
     * composite partition key those methods are unusable anyway — they throw — and
     * callers must use {@code findByKeys}, so the parameter falls back to
     * {@code Object}.
     */
    public static TypeName idType(TypeElement type, ProcessingEnvironment env) {
        List<KeyField> partitionKeys = partitionKeyFields(type, env);
        if (partitionKeys.size() != 1) {
            return ClassName.get(Object.class);
        }
        return TypeName.get(partitionKeys.get(0).field().asType()).box();
    }
}
