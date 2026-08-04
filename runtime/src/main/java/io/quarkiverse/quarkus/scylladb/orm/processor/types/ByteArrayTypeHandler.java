package io.quarkiverse.quarkus.scylladb.orm.processor.types;

import static io.quarkiverse.quarkus.scylladb.orm.processor.util.MapperUtil.*;

import java.nio.ByteBuffer;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.scylladb.orm.processor.TypeHandler;

/**
 * Maps {@code byte[]} fields to {@code blob} columns.
 * <p>
 * The driver only ships a {@code BLOB <-> ByteBuffer} codec, so without this handler the
 * generated {@code row.get(column, byte[].class)} fails at runtime with
 * {@code CodecNotFoundException}. Unlike {@code LocalDateTime}, the conversion is
 * unambiguous, so it is done here rather than pushed onto the user.
 */
public class ByteArrayTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return isByteArray(field);
    }

    @Override
    public boolean supportsType(String fqcn) {
        return "byte[]".equals(fqcn);
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String rowVar, String columnName) {
        String bufferVar = field.getSimpleName() + "Buffer";
        String arrayVar = field.getSimpleName() + "Bytes";

        // duplicate() so the row's buffer position is left alone — the same Row may be
        // read again, and a consumed buffer would silently yield an empty array.
        return CodeBlock.builder()
                .addStatement("$T $L = $L.getByteBuffer($S)", ByteBuffer.class, bufferVar, rowVar, columnName)
                .beginControlFlow("if ($L != null)", bufferVar)
                .addStatement("$T $L = $L.duplicate()", ByteBuffer.class, bufferVar + "Copy", bufferVar)
                .addStatement("byte[] $L = new byte[$L.remaining()]", arrayVar, bufferVar + "Copy")
                .addStatement("$L.get($L)", bufferVar + "Copy", arrayVar)
                .addStatement("$L.$L($L)", targetVar, resolveSetterName(field), arrayVar)
                .endControlFlow()
                .build();
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar, String columnName) {
        String valueVar = field.getSimpleName() + "Bytes";

        return CodeBlock.builder()
                .addStatement("byte[] $L = $L.$L()", valueVar, entityVar, resolveGetterName(field))
                .beginControlFlow("if ($L != null)", valueVar)
                .addStatement("$L.put($S, $T.wrap($L))", mapVar, columnName, ByteBuffer.class, valueVar)
                .endControlFlow()
                .build();
    }

    private static boolean isByteArray(VariableElement field) {
        return field.asType() instanceof ArrayType array
                && array.getComponentType().getKind() == TypeKind.BYTE;
    }
}
