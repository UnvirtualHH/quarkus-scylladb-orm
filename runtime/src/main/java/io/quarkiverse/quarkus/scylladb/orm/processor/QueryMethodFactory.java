package io.quarkiverse.quarkus.scylladb.orm.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.util.ElementFilter;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Queries;
import io.quarkiverse.quarkus.scylladb.orm.mapping.Query;
import io.quarkiverse.quarkus.scylladb.orm.mapping.ReturnType;

/**
 * Generates repository methods from @Queries / @Query annotations for Scylla.
 */
final class QueryMethodFactory {

    private static final Pattern PARAM_PATTERN = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    private QueryMethodFactory() {
    }

    static List<MethodSpec> buildQueryMethods(TypeElement entityType, boolean reactive, ProcessingEnvironment env) {
        List<MethodSpec> methods = new ArrayList<>();

        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation == null) {
            return methods;
        }

        for (Query q : queriesAnnotation.value()) {
            methods.add(buildMethodForQuery(entityType, q, reactive, env));
        }

        return methods;
    }

    private static MethodSpec buildMethodForQuery(
            TypeElement entityType,
            Query q,
            boolean reactive,
            ProcessingEnvironment env) {

        String methodName = q.name();
        String cql = q.cql();
        ReturnType returnType = q.returnType();

        List<String> paramNames = extractParamNames(cql);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("String query = $S", cql);

        for (String p : paramNames) {
            TypeName type = resolveParamType(entityType, p, q, env);
            mb.addParameter(type, p);
        }

        boolean useMap = paramNames.size() > 1;

        if (useMap) {
            mb.addStatement("$T<String,Object> params = new $T<>()", Map.class, HashMap.class);
            for (String p : paramNames) {
                mb.addStatement("params.put($S, $L)", p, p);
            }
        }

        switch (returnType) {
            case LIST -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Multi"),
                            TypeName.get(entityType.asType())));
                } else {
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get(List.class),
                            TypeName.get(entityType.asType())));
                }
                if (paramNames.isEmpty()) {
                    mb.addStatement("return query(query)");
                } else if (useMap) {
                    mb.addStatement("return query(query, params)");
                } else {
                    mb.addStatement("return query(query, $L)", paramNames.get(0));
                }
            }
            case SINGLE -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Uni"),
                            TypeName.get(entityType.asType())));
                } else {
                    mb.returns(TypeName.get(entityType.asType()));
                }
                if (paramNames.isEmpty()) {
                    mb.addStatement("return querySingle(query)");
                } else if (useMap) {
                    mb.addStatement("return querySingle(query, params)");
                } else {
                    mb.addStatement("return querySingle(query, $L)", paramNames.get(0));
                }
            }
            case VOID -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Uni"),
                            ClassName.get(Void.class)));
                    if (paramNames.isEmpty()) {
                        mb.addStatement("return execute(query)");
                    } else if (useMap) {
                        mb.addStatement("return execute(query, params)");
                    } else {
                        mb.addStatement("return execute(query, $L)", paramNames.get(0));
                    }
                } else {
                    mb.returns(TypeName.VOID);
                    if (paramNames.isEmpty()) {
                        mb.addStatement("execute(query)");
                    } else if (useMap) {
                        mb.addStatement("execute(query, params)");
                    } else {
                        mb.addStatement("execute(query, $L)", paramNames.get(0));
                    }
                }
            }
            case SCALAR -> {
                if (reactive) {
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Uni"),
                            ClassName.get(Long.class)));
                } else {
                    mb.returns(ClassName.get(Long.class));
                }
                if (paramNames.isEmpty()) {
                    mb.addStatement("return queryScalar(query, row -> row.getLong(0))");
                } else if (useMap) {
                    mb.addStatement("return queryScalar(query, row -> row.getLong(0), params)");
                } else {
                    mb.addStatement("return queryScalar(query, row -> row.getLong(0), $L)", paramNames.get(0));
                }
            }
        }

        return mb.build();
    }

    private static List<String> extractParamNames(String cql) {
        Matcher matcher = PARAM_PATTERN.matcher(cql);
        List<String> params = new ArrayList<>();
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    private static String joinParams(List<String> paramNames) {
        if (paramNames.isEmpty()) {
            return "";
        }
        return String.join(", ", paramNames);
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

        String pLower = paramName.toLowerCase(Locale.ROOT);
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getSimpleName().toString().toLowerCase(Locale.ROOT).equals(pLower)) {
                return TypeName.get(field.asType());
            }
        }

        return ClassName.get(Object.class);
    }

}
