package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
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

    private static final Pattern PARAM_PATTERN = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("(\\w+)\\s*(=|<|>|<=|>=|IN)\\s*\\?");

    private static final Set<String> STRUCTURAL_PARAMS = Set.of("limit", "order", "orderby", "sort", "offset");

    private QueryMethodFactory() {
    }

    static List<MethodSpec> buildQueryMethods(TypeElement entityType, boolean reactive, ProcessingEnvironment env) {
        List<MethodSpec> methods = new ArrayList<>();
        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation == null)
            return methods;

        for (Query q : queriesAnnotation.value()) {
            methods.add(buildMethodForQuery(entityType, q, reactive, env));
        }
        return methods;
    }

    private static MethodSpec buildMethodForQuery(TypeElement entityType, Query q, boolean reactive,
            ProcessingEnvironment env) {

        String methodName = q.name();
        String cql = q.cql();
        ReturnType returnType = q.returnType();

        if (returnType == ReturnType.LIST && cql.toUpperCase(Locale.ROOT).matches(".*\\bLIMIT\\s+1\\b.*")) {
            returnType = ReturnType.SINGLE;
        }

        // Extract params
        List<String> paramNames = extractParamNames(cql);
        List<String> structuralParams = new ArrayList<>();
        for (String p : paramNames) {
            if (isStructuralParam(p)) {
                structuralParams.add(p);
                cql = cql.replace(":" + p, "%s");
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

        // Build query string (handle structural params with String.format)
        if (!structuralParams.isEmpty()) {
            mb.addStatement("String query = String.format($S, $L)",
                    cql,
                    String.join(", ", structuralParams));
        } else {
            mb.addStatement("String query = $S", cql);
        }

        // Build params map only for bindable params
        boolean useMap = bindableParams.size() > 1;
        if (!bindableParams.isEmpty()) {
            mb.addStatement("$T<String,Object> params = new $T<>()", Map.class, HashMap.class);
            for (String p : bindableParams) {
                VariableElement field = findFieldByName(entityType, p);
                if (field != null && hasEnumeratedAnnotation(field)) {
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

        // Generate method body
        if (isSelect) {
            generateSelectMethodBody(mb, entityType, reactive, returnType, bindableParams, useMap);
        } else if (isConditional) {
            generateConditionalMethodBody(mb, entityType, reactive, returnType, bindableParams, useMap);
        } else if (isWrite || isSchema) {
            generateExecuteMethodBody(mb, reactive, returnType, bindableParams, useMap);
        } else {
            generateExecuteMethodBody(mb, reactive, returnType, bindableParams, useMap);
        }

        return mb.build();
    }

    // --------------------------------------------------
    // Query Type Helpers
    // --------------------------------------------------

    private static boolean isStructuralParam(String name) {
        return STRUCTURAL_PARAMS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isSelect(String cql) {
        return cql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT");
    }

    private static boolean isWrite(String cql) {
        return cql.toUpperCase(Locale.ROOT).matches(".*\\b(INSERT|UPDATE|DELETE|TRUNCATE)\\b.*");
    }

    private static boolean isConditional(String cql) {
        return cql.toUpperCase(Locale.ROOT).matches(".*\\bIF\\s+(EXISTS|NOT\\s+EXISTS|.*=.*)\\b.*");
    }

    private static boolean isSchema(String cql) {
        return cql.toUpperCase(Locale.ROOT).matches(".*\\b(CREATE|ALTER|DROP)\\b.*");
    }

    // --------------------------------------------------
    // Body Generation
    // --------------------------------------------------

    private static void generateSelectMethodBody(MethodSpec.Builder mb, TypeElement entityType, boolean reactive,
            ReturnType returnType, List<String> paramNames, boolean useMap) {
        switch (returnType) {
            case LIST -> {
                if (reactive)
                    mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Multi"),
                            TypeName.get(entityType.asType())));
                else
                    mb.returns(ParameterizedTypeName.get(ClassName.get(List.class),
                            TypeName.get(entityType.asType())));
                addCall(mb, "query", paramNames, useMap, true);
            }
            case SINGLE -> {
                if (reactive)
                    mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                            TypeName.get(entityType.asType())));
                else
                    mb.returns(TypeName.get(entityType.asType()));
                addCall(mb, "querySingle", paramNames, useMap, true);
            }
            case SCALAR -> {
                if (reactive)
                    mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                            ClassName.get(Long.class)));
                else
                    mb.returns(ClassName.get(Long.class));
                addCall(mb, "queryScalar", paramNames, useMap, true);
            }
            case VOID -> {
                if (reactive)
                    mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                            ClassName.get(Void.class)));
                else
                    mb.returns(TypeName.VOID);
                addCall(mb, "execute", paramNames, useMap, reactive);
            }
        }
    }

    private static void generateConditionalMethodBody(MethodSpec.Builder mb, TypeElement entityType, boolean reactive,
            ReturnType returnType, List<String> paramNames, boolean useMap) {
        if (reactive)
            mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                    TypeName.get(entityType.asType())));
        else
            mb.returns(TypeName.get(entityType.asType()));
        addCall(mb, "querySingle", paramNames, useMap, true);
    }

    private static void generateExecuteMethodBody(MethodSpec.Builder mb, boolean reactive, ReturnType returnType,
            List<String> paramNames, boolean useMap) {
        if (returnType == ReturnType.SCALAR) {
            if (reactive)
                mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                        ClassName.get(Long.class)));
            else
                mb.returns(ClassName.get(Long.class));
            addCall(mb, "queryScalar", paramNames, useMap, true);
            return;
        }
        if (reactive)
            mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                    ClassName.get(Void.class)));
        else
            mb.returns(TypeName.VOID);
        addCall(mb, "execute", paramNames, useMap, reactive);
    }

    private static void addCall(MethodSpec.Builder mb, String methodName, List<String> paramNames,
            boolean useMap, boolean hasReturn) {
        String prefix = hasReturn ? "return " : "";
        if (paramNames.isEmpty())
            mb.addStatement(prefix + "$L(query)", methodName);
        else if (useMap)
            mb.addStatement(prefix + "$L(query, params)", methodName);
        else
            mb.addStatement(prefix + "$L(query, $L)", methodName, String.join(", ", paramNames));
    }

    // --------------------------------------------------
    // Param Handling
    // --------------------------------------------------

    private static List<String> extractParamNames(String cql) {
        List<String> params = new ArrayList<>();
        Matcher matcher = PARAM_PATTERN.matcher(cql);
        while (matcher.find())
            params.add(matcher.group(1));
        if (params.isEmpty() && cql.contains("?"))
            params.addAll(guessParamNamesFromCql(cql));
        return params;
    }

    private static List<String> guessParamNamesFromCql(String cql) {
        List<String> guessed = new ArrayList<>();
        String upper = cql.toUpperCase(Locale.ROOT);

        Matcher m = COLUMN_PATTERN.matcher(cql);
        while (m.find()) {
            guessed.add(toCamelCase(m.group(1)));
        }

        int questionCount = (int) cql.chars().filter(ch -> ch == '?').count();
        while (guessed.size() < questionCount) {
            if (guessed.size() == questionCount - 1 && upper.contains("LIMIT ?")) {
                guessed.add("max");
            } else {
                guessed.add("param" + (guessed.size() + 1));
            }
        }
        return guessed;
    }

    private static String toCamelCase(String column) {
        String[] parts = column.split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT))
                        .append(parts[i].substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static TypeName resolveParamType(TypeElement entityType, String paramName, Query q, ProcessingEnvironment env) {
        for (Query.Param p : q.paramTypes()) {
            if (p.name().equals(paramName)) {
                try {
                    return TypeName.get(p.type());
                } catch (MirroredTypeException mte) {
                    return TypeName.get(mte.getTypeMirror());
                }
            }
        }
        if (paramName.equalsIgnoreCase("limit"))
            return ClassName.get(Integer.class);
        String pLower = paramName.toLowerCase(Locale.ROOT);
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getSimpleName().toString().toLowerCase(Locale.ROOT).equals(pLower))
                return TypeName.get(field.asType());
        }
        return ClassName.get(Object.class);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static VariableElement findFieldByName(TypeElement entityType, String name) {
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getSimpleName().toString().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static boolean hasEnumeratedAnnotation(VariableElement field) {
        return field.getAnnotationMirrors().stream()
                .anyMatch(a -> a.getAnnotationType().toString().endsWith(".Enumerated"));
    }
}