package io.quarkiverse.quarkus.scylladb.orm.processor.util;

import java.util.List;
import java.util.Locale;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public class MapperUtil {
    /**
     * Capitalizes the first character for a getter/setter name.
     * <p>
     * Uses {@link Character#toUpperCase(char)} rather than {@code String.toUpperCase()}:
     * the latter applies the default locale, so on a machine running under {@code tr_TR}
     * a field named {@code id} produced {@code set\u0130d} and the generated mapper did not
     * compile. The build machine's locale must not decide what a Java identifier is
     * called.
     */
    public static String capitalize(String input) {
        if (input == null || input.isEmpty())
            return input;
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    public static String resolveSetterName(VariableElement field) {
        return "set" + capitalize(field.getSimpleName().toString());
    }

    public static String resolveGetterName(VariableElement field) {
        String type = field.asType().toString();
        String base = capitalize(field.getSimpleName().toString());
        return ("boolean".equals(type)) ? "is" + base : "get" + base;
    }

    public static String getFieldType(VariableElement field) {
        String rawType = field.asType().toString();

        if (rawType.startsWith("java.util.List") || rawType.startsWith("java.util.Set")) {
            int start = rawType.indexOf('<');
            int end = rawType.indexOf('>');
            if (start != -1 && end != -1 && end > start) {
                return rawType.substring(start + 1, end);
            }
        }

        return rawType;
    }

    public static boolean isOfType(VariableElement field, String fqcn, Types types, Elements elements) {
        if (field.asType().getKind().isPrimitive()) {
            return field.asType().toString().equals(fqcn);
        }

        TypeElement te = elements.getTypeElement(fqcn);
        if (te == null) {
            return field.asType().toString().equals(fqcn);
        }

        return types.isSameType(types.erasure(field.asType()), te.asType());
    }

    /**
     * Whether a type can be written as {@code X.class}: a declared type with no type
     * arguments. The driver's typed row accessors ({@code getList}, {@code getSet},
     * {@code getMap}) take {@code Class} objects, so a wildcard or a nested generic has
     * nothing to pass them.
     */
    public static boolean isRawClass(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && ((DeclaredType) type).getTypeArguments().isEmpty();
    }

    /**
     * Whether every type argument of {@code type} can be written as {@code X.class}, and
     * there are as many as {@code expected}.
     */
    public static boolean hasClassLiteralTypeArguments(TypeMirror type, int expected) {
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        List<? extends TypeMirror> args = declared.getTypeArguments();
        return args.size() == expected && args.stream().allMatch(MapperUtil::isRawClass);
    }

    /**
     * A constant name derived from a fully qualified type name.
     * <p>
     * Derived from the FQN rather than the simple name, because the mapper deduplicates
     * these constants by name: two converters (or two enums) that share a simple name in
     * different packages collapsed into one constant, and every field but the first
     * silently used the wrong converter — or failed to compile, depending on whether the
     * two happened to have compatible signatures.
     */
    public static String constantNameFor(String fqcn) {
        return fqcn.replace('.', '_').replace("[]", "_ARRAY").toUpperCase(Locale.ROOT);
    }
}
