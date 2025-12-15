package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.scylladb.orm.enums.ReturnType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Queries;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Query;

/**
 * Factory for generating query methods from @Queries annotations.
 * Automatically adds .name() for @Enumerated parameters.
 * Supports structural parameters (e.g. :limit, :order, :sort) via String interpolation.
 */
final class QueryMethodFactory {

    // Regex patterns (compiled once for efficiency)
    private static final Pattern PARAM_PATTERN = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("(\\w+)\\s*(=|<|>|<=|>=|IN)\\s*\\?");
    private static final Pattern LIMIT_ONE_PATTERN = Pattern.compile("\\bLIMIT\\s+1\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_PATTERN = Pattern.compile("^\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_PATTERN = Pattern.compile("\\b(INSERT|UPDATE|DELETE|TRUNCATE)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEMA_PATTERN = Pattern.compile("\\b(CREATE|ALTER|DROP)\\b", Pattern.CASE_INSENSITIVE);

    // Structural parameters that should be interpolated, not bound
    private static final Set<String> STRUCTURAL_PARAMS = Set.of("limit", "order", "orderby", "sort", "offset");

    // Mutiny class names as constants
    private static final ClassName MUTINY_UNI = ClassName.get("io.smallrye.mutiny", "Uni");
    private static final ClassName MUTINY_MULTI = ClassName.get("io.smallrye.mutiny", "Multi");

    // Allowed values for structural parameters (SQL injection prevention)
    private static final Pattern SAFE_ORDER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\s+(ASC|DESC)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_COLUMN_PATTERN = Pattern
            .compile("^[A-Za-z_][A-Za-z0-9_]*(,\\s*[A-Za-z_][A-Za-z0-9_]*)*$");
    private static final Pattern SAFE_INTEGER_PATTERN = Pattern.compile("^\\d+$");

    private QueryMethodFactory() {
    }

    static List<MethodSpec> buildQueryMethods(TypeElement entityType, boolean reactive, ProcessingEnvironment env) {
        Objects.requireNonNull(entityType, "Entity type must not be null");
        Objects.requireNonNull(env, "ProcessingEnvironment must not be null");

        List<MethodSpec> methods = new ArrayList<>();
        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation == null) {
            return methods;
        }

        for (Query q : queriesAnnotation.value()) {
            if (q == null) {
                continue;
            }
            try {
                methods.add(buildMethodForQuery(entityType, q, reactive, env));
            } catch (Exception e) {
                // Log error but continue processing other queries
                env.getMessager().printMessage(
                        javax.tools.Diagnostic.Kind.ERROR,
                        "Failed to generate query method for @Query: " + e.getMessage(),
                        entityType);
            }
        }
        return methods;
    }

    private static MethodSpec buildMethodForQuery(TypeElement entityType, Query q, boolean reactive,
            ProcessingEnvironment env) {

        String methodName = q.name();
        String cql = q.cql();
        ReturnType returnType = q.returnType();

        // Validate inputs
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("Query method name must not be blank");
        }
        if (cql == null || cql.isBlank()) {
            throw new IllegalArgumentException("Query CQL must not be blank for method: " + methodName);
        }

        // Auto-detect SINGLE return type for LIMIT 1 queries
        if (returnType == ReturnType.LIST && LIMIT_ONE_PATTERN.matcher(cql).find()) {
            returnType = ReturnType.SINGLE;
        }

        // Extract and classify parameters
        List<String> paramNames = extractParamNames(cql);
        List<String> structuralParams = new ArrayList<>();
        String processedCql = cql;

        // Replace structural params with placeholders and validate them
        for (String p : paramNames) {
            if (isStructuralParam(p)) {
                structuralParams.add(p);
                // Use indexed placeholders for better control
                processedCql = processedCql.replace(":" + p, "%s");
            }
        }

        List<String> bindableParams = new ArrayList<>(paramNames);
        bindableParams.removeAll(structuralParams);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC);

        // Add parameters
        for (String p : paramNames) {
            TypeName type = resolveParamType(entityType, p, q, env);
            mb.addParameter(type, p);
        }

        // Build query string with validation for structural params
        if (!structuralParams.isEmpty()) {
            // Add validation for structural parameters
            for (String sp : structuralParams) {
                addStructuralParamValidation(mb, sp);
            }

            mb.addStatement("String query = String.format($S, $L)",
                    processedCql,
                    String.join(", ", structuralParams));
        } else {
            mb.addStatement("String query = $S", processedCql);
        }

        // Build params map only for bindable params
        boolean useMap = !bindableParams.isEmpty();
        if (!bindableParams.isEmpty()) {
            mb.addStatement("$T<String, Object> params = new $T<>()", Map.class, HashMap.class);
            for (String p : bindableParams) {
                VariableElement field = findFieldByName(entityType, p);
                if (field != null && isEnumField(field) && hasEnumeratedAnnotation(field)) {
                    mb.addStatement("params.put($S, $L != null ? $L.name() : null)", p, p, p);
                } else {
                    mb.addStatement("params.put($S, $L)", p, p);
                }
            }
        }

        // Detect query type
        boolean isSelect = isSelect(cql);
        boolean isWrite = isWrite(cql);
        boolean isConditional = isConditional(cql);
        boolean isSchema = isSchema(cql);
        boolean isReturning = isReturning(cql);

        // Generate method body based on query type
        if (isSelect) {
            generateSelectMethodBody(mb, entityType, reactive, returnType, bindableParams, useMap);
        } else if (isConditional && !isReturning) {
            generateConditionalMethodBody(mb, entityType, reactive, returnType, bindableParams, useMap);
        } else if (isReturning) {
            generateSelectMethodBody(mb, entityType, reactive, returnType, bindableParams, useMap);
        } else if (isWrite || isSchema) {
            generateExecuteMethodBody(mb, reactive, returnType, bindableParams, useMap);
        } else {
            generateExecuteMethodBody(mb, reactive, returnType, bindableParams, useMap);
        }

        return mb.build();
    }

    // --------------------------------------------------
    // Validation for SQL Injection Prevention
    // --------------------------------------------------

    private static void addStructuralParamValidation(MethodSpec.Builder mb, String paramName) {
        String paramLower = paramName.toLowerCase(Locale.ROOT);

        if (paramLower.equals("order") || paramLower.equals("orderby") || paramLower.equals("sort")) {
            // Validate ORDER BY clause format: "column ASC" or "column DESC"
            mb.beginControlFlow("if ($L != null && !$L.matches($S))", paramName, paramName,
                    SAFE_ORDER_PATTERN.pattern());
            mb.addStatement(
                    "throw new $T($S + $L)",
                    IllegalArgumentException.class,
                    "Invalid order parameter format (expected 'column ASC/DESC'): ",
                    paramName);
            mb.endControlFlow();
        } else if (paramLower.equals("limit") || paramLower.equals("offset")) {
            // Validate numeric values
            mb.beginControlFlow("if ($L != null)", paramName);
            mb.addStatement("String $LStr = String.valueOf($L)", paramName, paramName);
            mb.beginControlFlow("if (!$LStr.matches($S))", paramName, SAFE_INTEGER_PATTERN.pattern());
            mb.addStatement(
                    "throw new $T($S + $LStr)",
                    IllegalArgumentException.class,
                    "Invalid numeric parameter: ",
                    paramName);
            mb.endControlFlow();
            mb.endControlFlow();
        }
    }

    // --------------------------------------------------
    // Query Type Helpers
    // --------------------------------------------------

    private static boolean isStructuralParam(String name) {
        return STRUCTURAL_PARAMS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isSelect(String cql) {
        return SELECT_PATTERN.matcher(cql.trim()).find();
    }

    private static boolean isWrite(String cql) {
        return WRITE_PATTERN.matcher(cql).find();
    }

    private static boolean isConditional(String cql) {
        String upper = cql.toUpperCase(Locale.ROOT);
        return upper.contains(" IF ") && !upper.contains(" RETURNING ");
    }

    private static boolean isReturning(String cql) {
        return cql.toUpperCase(Locale.ROOT).contains(" RETURNING ");
    }

    private static boolean isSchema(String cql) {
        return SCHEMA_PATTERN.matcher(cql).find();
    }

    // --------------------------------------------------
    // Body Generation
    // --------------------------------------------------

    private static void generateSelectMethodBody(MethodSpec.Builder mb, TypeElement entityType, boolean reactive,
            ReturnType returnType, List<String> paramNames, boolean useMap) {
        switch (returnType) {
            case LIST -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_MULTI, TypeName.get(entityType.asType())));
                } else {
                    mb.returns(ParameterizedTypeName.get(ClassName.get(List.class),
                            TypeName.get(entityType.asType())));
                }
                addCall(mb, "query", paramNames, useMap, true);
            }
            case SINGLE -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, TypeName.get(entityType.asType())));
                } else {
                    mb.returns(TypeName.get(entityType.asType()));
                }
                addCall(mb, "querySingle", paramNames, useMap, true);
            }
            case SCALAR -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Long.class)));
                } else {
                    mb.returns(ClassName.get(Long.class));
                }
                addCall(mb, "queryScalar", paramNames, useMap, true);
            }
            case VOID -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Void.class)));
                } else {
                    mb.returns(TypeName.VOID);
                }
                addCall(mb, "execute", paramNames, useMap, reactive);
            }
        }
    }

    private static void generateConditionalMethodBody(
            MethodSpec.Builder mb,
            TypeElement entityType,
            boolean reactive,
            ReturnType returnType,
            List<String> paramNames,
            boolean useMap) {
        switch (returnType) {
            case VOID -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Void.class)));
                } else {
                    mb.returns(TypeName.VOID);
                }
                addCall(mb, "execute", paramNames, useMap, reactive);
            }
            case SCALAR -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Long.class)));
                } else {
                    mb.returns(ClassName.get(Long.class));
                }
                addCall(mb, "queryScalar", paramNames, useMap, true);
            }
            case LIST, SINGLE -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, TypeName.get(entityType.asType())));
                } else {
                    mb.returns(TypeName.get(entityType.asType()));
                }
                addCall(mb, "querySingle", paramNames, useMap, true);
            }
        }
    }

    private static void generateExecuteMethodBody(MethodSpec.Builder mb, boolean reactive, ReturnType returnType,
            List<String> paramNames, boolean useMap) {
        if (returnType == ReturnType.SCALAR) {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Long.class)));
            } else {
                mb.returns(ClassName.get(Long.class));
            }
            addCall(mb, "queryScalar", paramNames, useMap, true);
            return;
        }
        if (reactive) {
            mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Void.class)));
        } else {
            mb.returns(TypeName.VOID);
        }
        addCall(mb, "execute", paramNames, useMap, reactive);
    }

    private static void addCall(MethodSpec.Builder mb, String methodName, List<String> paramNames,
            boolean useMap, boolean hasReturn) {
        String prefix = hasReturn ? "return " : "";
        if (paramNames.isEmpty()) {
            mb.addStatement(prefix + "$L(query)", methodName);
        } else if (useMap) {
            mb.addStatement(prefix + "$L(query, params)", methodName);
        } else {
            mb.addStatement(prefix + "$L(query, $L)", methodName, String.join(", ", paramNames));
        }
    }

    // --------------------------------------------------
    // Param Handling
    // --------------------------------------------------

    private static List<String> extractParamNames(String cql) {
        List<String> params = new ArrayList<>();
        Matcher matcher = PARAM_PATTERN.matcher(cql);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }

        // Fallback to positional parameter guessing if named params not found
        if (params.isEmpty() && cql.contains("?")) {
            params.addAll(guessParamNamesFromCql(cql));
        }

        return params;
    }

    private static List<String> guessParamNamesFromCql(String cql) {
        List<String> guessed = new ArrayList<>();
        String upper = cql.toUpperCase(Locale.ROOT);

        // Try to extract column names from WHERE clauses
        Matcher m = COLUMN_PATTERN.matcher(cql);
        while (m.find()) {
            guessed.add(toCamelCase(m.group(1)));
        }

        // Count total ? placeholders
        int questionCount = (int) cql.chars().filter(ch -> ch == '?').count();

        // Fill remaining params with generic names
        while (guessed.size() < questionCount) {
            if (guessed.size() == questionCount - 1 && upper.matches(".*\\bLIMIT\\s+\\?.*")) {
                guessed.add("limit");
            } else {
                guessed.add("param" + (guessed.size() + 1));
            }
        }
        return guessed;
    }

    private static String toCamelCase(String column) {
        if (column == null || column.isEmpty()) {
            return column;
        }

        String[] parts = column.split("_");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT))
                        .append(parts[i].substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static TypeName resolveParamType(TypeElement entityType, String paramName, Query q, ProcessingEnvironment env) {
        // Check explicit param types first
        for (Query.Param p : q.paramTypes()) {
            if (p.name().equals(paramName)) {
                try {
                    return TypeName.get(p.type());
                } catch (MirroredTypeException mte) {
                    return TypeName.get(mte.getTypeMirror());
                }
            }
        }

        // Special handling for common structural params
        String paramLower = paramName.toLowerCase(Locale.ROOT);
        if (paramLower.equals("limit") || paramLower.equals("offset")) {
            return ClassName.get(Integer.class);
        }
        if (paramLower.equals("order") || paramLower.equals("orderby") || paramLower.equals("sort")) {
            return ClassName.get(String.class);
        }

        // Try to match with entity field (case-sensitive)
        VariableElement field = findFieldByName(entityType, paramName);
        if (field != null) {
            return TypeName.get(field.asType());
        }

        // Fallback to Object
        return ClassName.get(Object.class);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static VariableElement findFieldByName(TypeElement entityType, String name) {
        // First try exact match (case-sensitive)
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getSimpleName().toString().equals(name)) {
                return field;
            }
        }

        // Fallback to case-insensitive match
        String nameLower = name.toLowerCase(Locale.ROOT);
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getSimpleName().toString().toLowerCase(Locale.ROOT).equals(nameLower)) {
                return field;
            }
        }

        return null;
    }

    private static boolean isEnumField(VariableElement field) {
        return field.asType().getKind() == TypeKind.DECLARED
                && field.asType().toString().contains("Enum");
    }

    private static boolean hasEnumeratedAnnotation(VariableElement field) {
        return field.getAnnotationMirrors().stream()
                .anyMatch(a -> a.getAnnotationType().toString().endsWith(".Enumerated"));
    }
}