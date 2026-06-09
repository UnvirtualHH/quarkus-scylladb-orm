package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.scylladb.orm.enums.ReturnType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Queries;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Query;
import io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil;

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
    // Schema-altering / TRUNCATE statements always begin with the verb, so anchor the
    // guard to the leading keyword. This avoids false positives on string literals or
    // identifiers that merely contain these words (e.g. WHERE action = 'DROP').
    private static final Pattern SCHEMA_OR_TRUNCATE_LEADING = Pattern.compile(
            "^\\s*(CREATE|ALTER|DROP|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);

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

        // Track method names to detect duplicates
        Set<String> seenMethodNames = new HashSet<>();

        for (Query q : queriesAnnotation.value()) {
            if (q == null) {
                continue;
            }

            // Check for duplicate method names
            String methodName = q.name();
            if (methodName != null && !methodName.isBlank()) {
                if (!seenMethodNames.add(methodName)) {
                    env.getMessager().printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate @Query method name: '" + methodName + "'. Each query must have a unique name.",
                            entityType);
                    continue;
                }
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

        // Least-privilege guard: reject schema-altering / TRUNCATE statements unless the
        // query explicitly opts in. A high-traffic application role should not hold
        // schema/truncate permissions, and this prevents such statements from being
        // generated by accident.
        if (SCHEMA_OR_TRUNCATE_LEADING.matcher(cql).find() && !q.allowSchemaChanges()) {
            throw new IllegalArgumentException(
                    "Schema-altering (CREATE/ALTER/DROP) or TRUNCATE statements are not allowed in @Query "
                            + "by default for method '" + methodName + "'. Set @Query(allowSchemaChanges = true) "
                            + "to opt in, and ensure the database role has the required grants.");
        }

        // Auto-detect SINGLE return type for LIMIT 1 queries
        if (returnType == ReturnType.LIST && LIMIT_ONE_PATTERN.matcher(cql).find()) {
            returnType = ReturnType.SINGLE;
        }

        // Extract and classify parameters
        List<String> paramNames = extractParamNames(cql);
        List<String> structuralParams = new ArrayList<>();
        for (String p : paramNames) {
            if (isStructuralParam(p)) {
                structuralParams.add(p);
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

        // Build the processed CQL. Structural params become %s for String.format (with any
        // literal % escaped); bindable :name params become positional ? placeholders.
        // Both passes are token-safe to avoid prefix collisions like :id and :id2.
        String processedCql;
        if (!structuralParams.isEmpty()) {
            processedCql = replaceStructuralParamsWithFormat(cql, new HashSet<>(structuralParams));
            processedCql = replaceBindableParamsWithPositional(processedCql, bindableParams);
        } else {
            processedCql = replaceBindableParamsWithPositional(cql, bindableParams);
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

        List<String> bindableParamExpressions = new ArrayList<>(bindableParams.size());
        for (String p : bindableParams) {
            VariableElement field = findFieldByName(entityType, p);
            if (field != null && isEnumField(field, env) && hasEnumeratedAnnotation(field)) {
                bindableParamExpressions.add(p + " != null ? " + p + ".name() : null");
            } else {
                bindableParamExpressions.add(p);
            }
        }

        // --- Projection support: check for resultClass ---
        TypeMirror resultClassMirror = getResultClassMirror(q);
        boolean isProjection = resultClassMirror != null
                && resultClassMirror.getKind() != TypeKind.VOID;

        if (isProjection) {
            return buildProjectionMethod(reactive, mb, returnType, resultClassMirror,
                    bindableParamExpressions, env);
        }

        // Detect query type
        boolean isSelect = isSelect(cql);
        boolean isWrite = isWrite(cql);
        boolean isConditional = isConditional(cql);
        boolean isSchema = isSchema(cql);
        boolean isReturning = isReturning(cql);

        // Generate method body based on query type
        if (isSelect) {
            generateSelectMethodBody(mb, entityType, reactive, returnType, bindableParamExpressions);
        } else if (isConditional && !isReturning) {
            generateConditionalMethodBody(mb, entityType, reactive, returnType, bindableParamExpressions);
        } else if (isReturning) {
            generateSelectMethodBody(mb, entityType, reactive, returnType, bindableParamExpressions);
        } else if (isWrite || isSchema) {
            generateExecuteMethodBody(mb, reactive, returnType, bindableParamExpressions);
        } else {
            generateExecuteMethodBody(mb, reactive, returnType, bindableParamExpressions);
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
            ReturnType returnType, List<String> paramExpressions) {
        switch (returnType) {
            case LIST -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_MULTI, TypeName.get(entityType.asType())));
                } else {
                    mb.returns(ParameterizedTypeName.get(ClassName.get(List.class),
                            TypeName.get(entityType.asType())));
                }
                addCall(mb, "query", paramExpressions, true);
            }
            case SINGLE -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, TypeName.get(entityType.asType())));
                } else {
                    mb.returns(TypeName.get(entityType.asType()));
                }
                addCall(mb, "querySingle", paramExpressions, true);
            }
            case SCALAR -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Long.class)));
                } else {
                    mb.returns(ClassName.get(Long.class));
                }
                addCall(mb, "queryScalar", paramExpressions, true);
            }
            case VOID -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Void.class)));
                } else {
                    mb.returns(TypeName.VOID);
                }
                addCall(mb, "execute", paramExpressions, reactive);
            }
        }
    }

    private static void generateConditionalMethodBody(
            MethodSpec.Builder mb,
            TypeElement entityType,
            boolean reactive,
            ReturnType returnType,
            List<String> paramExpressions) {
        switch (returnType) {
            case VOID -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Void.class)));
                } else {
                    mb.returns(TypeName.VOID);
                }
                addCall(mb, "execute", paramExpressions, reactive);
            }
            case SCALAR -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Long.class)));
                } else {
                    mb.returns(ClassName.get(Long.class));
                }
                addCall(mb, "queryScalar", paramExpressions, true);
            }
            case LIST, SINGLE -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(MUTINY_UNI, TypeName.get(entityType.asType())));
                } else {
                    mb.returns(TypeName.get(entityType.asType()));
                }
                addCall(mb, "querySingle", paramExpressions, true);
            }
        }
    }

    private static void generateExecuteMethodBody(MethodSpec.Builder mb, boolean reactive, ReturnType returnType,
            List<String> paramExpressions) {
        if (returnType == ReturnType.SCALAR) {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Long.class)));
            } else {
                mb.returns(ClassName.get(Long.class));
            }
            addCall(mb, "queryScalar", paramExpressions, true);
            return;
        }
        if (reactive) {
            mb.returns(ParameterizedTypeName.get(MUTINY_UNI, ClassName.get(Void.class)));
        } else {
            mb.returns(TypeName.VOID);
        }
        addCall(mb, "execute", paramExpressions, reactive);
    }

    private static void addCall(MethodSpec.Builder mb, String methodName, List<String> paramExpressions, boolean hasReturn) {
        String prefix = hasReturn ? "return " : "";
        if (paramExpressions.isEmpty()) {
            mb.addStatement(prefix + "$L(query)", methodName);
        } else {
            mb.addStatement(prefix + "$L(query, $L)", methodName, String.join(", ", paramExpressions));
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

        // Fallback to Object with a warning
        env.getMessager().printMessage(
                javax.tools.Diagnostic.Kind.WARNING,
                "Could not resolve type for parameter '" + paramName + "' in entity " +
                        entityType.getSimpleName() + ". Using Object type. " +
                        "Consider adding explicit @Query.Param type annotation.",
                entityType);
        return ClassName.get(Object.class);
    }

    /**
     * Replaces structural params (:limit, :order, ...) with {@code %s} placeholders for
     * {@link String#format}, token-safe (no prefix collisions). Any literal {@code %} in
     * the surrounding CQL is escaped to {@code %%} so it survives String.format. Bindable
     * {@code :name} params are left untouched for the subsequent positional pass.
     */
    private static String replaceStructuralParamsWithFormat(String cql, Set<String> structural) {
        Matcher matcher = PARAM_PATTERN.matcher(cql);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(escapeFormat(cql.substring(last, matcher.start())));
            if (structural.contains(matcher.group(1))) {
                out.append("%s");
            } else {
                // Keep :name for the positional pass (escape any % defensively).
                out.append(escapeFormat(matcher.group(0)));
            }
            last = matcher.end();
        }
        out.append(escapeFormat(cql.substring(last)));
        return out.toString();
    }

    private static String escapeFormat(String s) {
        return s.replace("%", "%%");
    }

    private static String replaceBindableParamsWithPositional(String cql, List<String> bindableParams) {
        if (bindableParams.isEmpty()) {
            return cql;
        }
        Set<String> bindable = new HashSet<>(bindableParams);
        Matcher matcher = PARAM_PATTERN.matcher(cql);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (bindable.contains(name)) {
                matcher.appendReplacement(out, "?");
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(out);
        return out.toString();
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

    private static boolean isEnumField(VariableElement field, ProcessingEnvironment env) {
        TypeMirror fieldType = field.asType();
        if (fieldType.getKind() != TypeKind.DECLARED) {
            return false;
        }

        DeclaredType declaredType = (DeclaredType) fieldType;
        Element element = declaredType.asElement();
        if (element.getKind() != ElementKind.ENUM) {
            return false;
        }

        // Double-check by verifying it's a subtype of java.lang.Enum
        TypeElement enumTypeElement = env.getElementUtils().getTypeElement("java.lang.Enum");
        if (enumTypeElement == null) {
            // Fallback if Enum type not found (shouldn't happen)
            return element.getKind() == ElementKind.ENUM;
        }

        TypeMirror enumType = env.getTypeUtils().erasure(enumTypeElement.asType());
        TypeMirror erasedFieldType = env.getTypeUtils().erasure(fieldType);
        return env.getTypeUtils().isSubtype(erasedFieldType, enumType);
    }

    private static boolean hasEnumeratedAnnotation(VariableElement field) {
        return field.getAnnotationMirrors().stream()
                .anyMatch(a -> a.getAnnotationType().toString().endsWith(".Enumerated"));
    }

    // --------------------------------------------------
    // Projection Support
    // --------------------------------------------------

    private static TypeMirror getResultClassMirror(Query q) {
        try {
            q.resultClass();
            return null;
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
    }

    private static MethodSpec buildProjectionMethod(
            boolean reactive,
            MethodSpec.Builder mb,
            ReturnType returnType,
            TypeMirror resultClassMirror,
            List<String> paramExpressions,
            ProcessingEnvironment env) {

        TypeElement resultType = (TypeElement) env.getTypeUtils().asElement(resultClassMirror);
        ClassName resultClassName = ClassName.get(resultType);

        boolean isList = (returnType == ReturnType.LIST);

        // Determine repository method to call
        String repoCall = isList ? "queryProjectionList" : "queryProjection";

        // Set return type
        if (isList) {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(MUTINY_MULTI, resultClassName));
            } else {
                mb.returns(ParameterizedTypeName.get(ClassName.get(List.class), resultClassName));
            }
        } else {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(MUTINY_UNI, resultClassName));
            } else {
                mb.returns(resultClassName);
            }
        }

        // Build the mapping lambda
        CodeBlock mapperLambda;
        if (resultType.getKind() == ElementKind.RECORD) {
            mapperLambda = buildRecordMapperLambda(resultType, resultClassName, env);
        } else {
            mapperLambda = buildDtoMapperLambda(resultType, resultClassName, env);
        }

        // Build the return statement
        if (paramExpressions.isEmpty()) {
            mb.addStatement("return $L(query, $L)", repoCall, mapperLambda);
        } else {
            mb.addStatement("return $L(query, $L, $L)", repoCall, mapperLambda,
                    String.join(", ", paramExpressions));
        }

        return mb.build();
    }

    private static CodeBlock buildRecordMapperLambda(
            TypeElement recordType,
            ClassName resultClassName,
            ProcessingEnvironment env) {

        List<? extends RecordComponentElement> components = recordType.getRecordComponents();

        CodeBlock.Builder lambda = CodeBlock.builder();
        lambda.add("row -> new $T(", resultClassName);

        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                lambda.add(", ");
            }
            RecordComponentElement comp = components.get(i);
            String name = comp.getSimpleName().toString();
            TypeMirror type = comp.asType();
            lambda.add(generateValueExtraction(name, type));
        }

        lambda.add(")");
        return lambda.build();
    }

    private static CodeBlock buildDtoMapperLambda(
            TypeElement dtoType,
            ClassName resultClassName,
            ProcessingEnvironment env) {

        List<VariableElement> fields = ElementFilter.fieldsIn(dtoType.getEnclosedElements())
                .stream()
                .filter(f -> !f.getModifiers().contains(Modifier.STATIC))
                .toList();

        CodeBlock.Builder lambda = CodeBlock.builder();
        lambda.add("row -> {\n");
        lambda.indent();
        lambda.addStatement("$T _result = new $T()", resultClassName, resultClassName);

        for (VariableElement field : fields) {
            String name = field.getSimpleName().toString();
            String setter = "set" + MapperUtil.capitalize(name);
            TypeMirror type = field.asType();
            CodeBlock extraction = generateValueExtraction(name, type);

            if (type.getKind().isPrimitive()) {
                lambda.addStatement("_result.$L($L)", setter, extraction);
            } else {
                String varName = name + "Val";
                lambda.addStatement("var $L = $L", varName, extraction);
                lambda.beginControlFlow("if ($L != null)", varName);
                lambda.addStatement("_result.$L($L)", setter, varName);
                lambda.endControlFlow();
            }
        }

        lambda.addStatement("return _result");
        lambda.unindent();
        lambda.add("}");
        return lambda.build();
    }

    private static CodeBlock generateValueExtraction(String columnName, TypeMirror type) {
        String fqcn = type.toString();

        // Primitives — no null check, use typed Row accessors
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT -> CodeBlock.of("row.getInt($S)", columnName);
                case LONG -> CodeBlock.of("row.getLong($S)", columnName);
                case BOOLEAN -> CodeBlock.of("row.getBoolean($S)", columnName);
                case DOUBLE -> CodeBlock.of("row.getDouble($S)", columnName);
                case FLOAT -> CodeBlock.of("row.getFloat($S)", columnName);
                case SHORT -> CodeBlock.of("row.getShort($S)", columnName);
                case BYTE -> CodeBlock.of("row.getByte($S)", columnName);
                default -> CodeBlock.of("row.get($S, $T.class)", columnName, ClassName.bestGuess(fqcn));
            };
        }

        // Object types — use generic row.get() with type class
        return switch (fqcn) {
            case "java.lang.String" ->
                CodeBlock.of("row.getString($S)", columnName);
            case "java.util.UUID" ->
                CodeBlock.of("row.getUuid($S)", columnName);
            case "java.time.Instant" ->
                CodeBlock.of("row.getInstant($S)", columnName);
            case "java.time.LocalDate" ->
                CodeBlock.of("row.getLocalDate($S)", columnName);
            case "java.time.LocalTime" ->
                CodeBlock.of("row.getLocalTime($S)", columnName);
            case "java.math.BigDecimal" ->
                CodeBlock.of("row.getBigDecimal($S)", columnName);
            case "java.math.BigInteger" ->
                CodeBlock.of("row.getBigInteger($S)", columnName);
            case "java.nio.ByteBuffer" ->
                CodeBlock.of("row.getByteBuffer($S)", columnName);
            case "java.net.InetAddress" ->
                CodeBlock.of("row.getInetAddress($S)", columnName);
            default ->
                CodeBlock.of("row.get($S, $T.class)", columnName, ClassName.bestGuess(fqcn));
        };
    }
}
