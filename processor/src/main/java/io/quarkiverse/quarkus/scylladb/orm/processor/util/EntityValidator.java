package io.quarkiverse.quarkus.scylladb.orm.processor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

import io.quarkiverse.quarkus.scylladb.orm.mapping.ClusteringKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.PartitionKey;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Transient;
import io.quarkiverse.quarkus.scylladb.orm.processor.util.EntityFields.KeyField;

/**
 * Build-time checks on an entity's shape.
 * <p>
 * The generators used to emit code for anything annotated {@code @Table}, so a mistake in
 * the entity surfaced far from its cause: a missing {@code @PartitionKey} became an
 * {@code ArrayIndexOutOfBoundsException} inside the repository on the first
 * {@code exists()} call, two fields mapping to the same column became a server-side CQL
 * error naming neither field, and duplicate key ordinals silently reordered the primary
 * key so that {@code findByKeys} bound the arguments to the wrong columns. All of that is
 * knowable while compiling the entity, so it is reported there — against the offending
 * field, where the fix is.
 * <p>
 * Every problem found is reported, not just the first: fixing one and recompiling to
 * discover the next is a slow way to learn about four of them.
 */
public final class EntityValidator {

    private EntityValidator() {
    }

    /**
     * Reports every problem found on {@code entityType}.
     *
     * @return {@code true} when the entity is fit to generate from. When {@code false},
     *         errors have already been printed and generation must be skipped — emitting
     *         a mapper anyway would bury the real diagnostics under compile errors in
     *         generated code.
     */
    public static boolean validate(TypeElement entityType, ProcessingEnvironment env) {
        List<VariableElement> mapped = EntityFields.mappedFields(entityType, env);
        List<KeyField> partitionKeys = EntityFields.partitionKeyFields(entityType, env);
        List<KeyField> clusteringKeys = EntityFields.clusteringKeyFields(entityType, env);

        boolean ok = true;
        ok &= requirePartitionKey(entityType, partitionKeys, env);
        ok &= rejectTransientKeys(entityType, env);
        ok &= rejectFieldsThatAreBothKeys(entityType, env);
        ok &= rejectDuplicateOrdinals(entityType, partitionKeys, "@PartitionKey", env);
        ok &= rejectDuplicateOrdinals(entityType, clusteringKeys, "@ClusteringKey", env);
        ok &= rejectDuplicateColumns(entityType, mapped, env);
        return ok;
    }

    private static boolean requirePartitionKey(TypeElement entityType, List<KeyField> partitionKeys,
            ProcessingEnvironment env) {
        if (!partitionKeys.isEmpty()) {
            return true;
        }
        error(env, entityType,
                "Entity " + entityType.getSimpleName() + " declares no @PartitionKey. Every Scylla table needs "
                        + "at least one partition key column — without it findById/exists/delete cannot build a "
                        + "WHERE clause and every read degenerates into a full table scan.");
        return false;
    }

    private static boolean rejectTransientKeys(TypeElement entityType, ProcessingEnvironment env) {
        boolean ok = true;
        for (VariableElement field : EntityFields.allFields(entityType, env)) {
            if (field.getAnnotation(Transient.class) == null) {
                continue;
            }
            if (field.getAnnotation(PartitionKey.class) != null || field.getAnnotation(ClusteringKey.class) != null) {
                error(env, field,
                        "Field '" + field.getSimpleName() + "' is annotated @Transient as well as a key annotation. "
                                + "A key column has to be written and read, so the two cannot both hold; the key "
                                + "would be silently dropped from the mapper while the repository still built a "
                                + "WHERE clause around it.");
                ok = false;
            }
        }
        return ok;
    }

    private static boolean rejectFieldsThatAreBothKeys(TypeElement entityType, ProcessingEnvironment env) {
        boolean ok = true;
        for (VariableElement field : EntityFields.allFields(entityType, env)) {
            if (field.getAnnotation(PartitionKey.class) != null && field.getAnnotation(ClusteringKey.class) != null) {
                error(env, field,
                        "Field '" + field.getSimpleName() + "' is annotated both @PartitionKey and @ClusteringKey. "
                                + "It would appear twice in the primary key WHERE clause, and findByKeys would "
                                + "expect one more argument than the table has key columns.");
                ok = false;
            }
        }
        return ok;
    }

    private static boolean rejectDuplicateOrdinals(TypeElement entityType, List<KeyField> keys, String annotation,
            ProcessingEnvironment env) {
        if (keys.size() < 2) {
            return true;
        }
        Map<Integer, List<KeyField>> byOrdinal = new LinkedHashMap<>();
        for (KeyField key : keys) {
            byOrdinal.computeIfAbsent(key.ordinal(), o -> new ArrayList<>()).add(key);
        }

        boolean ok = true;
        for (Map.Entry<Integer, List<KeyField>> entry : byOrdinal.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String names = entry.getValue().stream().map(k -> k.field().getSimpleName().toString())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            error(env, entry.getValue().get(0).field(),
                    "Several " + annotation + " fields share ordinal " + entry.getKey() + ": " + names
                            + ". The ordinal fixes the column order of the primary key, so a tie leaves that order "
                            + "up to the compiler's field order — and findByKeys/deleteByKeys would bind their "
                            + "arguments to whichever column happened to come first. Give each key a distinct "
                            + "ordinal.");
            ok = false;
        }
        return ok;
    }

    private static boolean rejectDuplicateColumns(TypeElement entityType, List<VariableElement> mapped,
            ProcessingEnvironment env) {
        Map<String, List<VariableElement>> byColumn = new LinkedHashMap<>();
        for (VariableElement field : mapped) {
            // Column names are case-insensitive in CQL unless quoted, and the generated
            // statements never quote them.
            byColumn.computeIfAbsent(EntityFields.resolveColumnName(field).toLowerCase(Locale.ROOT),
                    c -> new ArrayList<>()).add(field);
        }

        boolean ok = true;
        for (Map.Entry<String, List<VariableElement>> entry : byColumn.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            Set<String> owners = new LinkedHashSet<>();
            for (VariableElement field : entry.getValue()) {
                owners.add(field.getEnclosingElement().getSimpleName() + "." + field.getSimpleName());
            }
            error(env, entry.getValue().get(0),
                    "Column '" + entry.getKey() + "' is mapped by more than one field: " + String.join(", ", owners)
                            + ". The generated INSERT would list it twice, which Scylla rejects. This usually means "
                            + "a field shadows an inherited one, or two @Column annotations resolve to the same "
                            + "name — rename one, or mark it @Transient.");
            ok = false;
        }
        return ok;
    }

    private static void error(ProcessingEnvironment env, Element at, String message) {
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at);
    }
}
