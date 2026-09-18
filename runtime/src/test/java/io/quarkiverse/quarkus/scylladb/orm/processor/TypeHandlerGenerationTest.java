package io.quarkiverse.quarkus.scylladb.orm.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three {@code TypeHandler} implementations emit the code that runs per row per
 * field — the hottest path in the extension — and had no direct coverage at all.
 */
class TypeHandlerGenerationTest {

    private static final String PKG = "test.model";

    private static String mapperFor(String fields, String... extraSources) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < extraSources.length; i += 2) {
            sources.put(extraSources[i], extraSources[i + 1]);
        }
        sources.put(PKG + ".Sample", """
                package test.model;

                import java.util.*;
                import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                import io.quarkiverse.quarkus.scylladb.orm.enums.*;

                @Table("sample")
                public class Sample {
                    @PartitionKey private UUID id;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                %s
                }
                """.formatted(fields));

        GeneratedSources.Result result = GeneratedSources.compile(sources);
        assertTrue(result.success(), result.errorText());
        return result.source(PKG + ".SampleMapper");
    }

    private static final String STATUS_ENUM = """
            package test.model;
            public enum Status { ACTIVE, CLOSED }
            """;

    @Test
    @DisplayName("STRING enums round-trip through name() and read the column once")
    void enumStringMapping() {
        String source = mapperFor("""
                    @Enumerated(EnumType.STRING) private Status status;
                    public Status getStatus() { return status; }
                    public void setStatus(Status s) { this.status = s; }
                """, PKG + ".Status", STATUS_ENUM);

        assertTrue(source.contains("Status.valueOf("), source);
        assertTrue(source.contains(".name()"), source);
        // One read per field per row - it used to call row.get twice, once for the null
        // check and once for the value.
        assertEquals(1, countOccurrences(source, "row.get(\"status\""), source);
    }

    @Test
    @DisplayName("ORDINAL enums cache values() and bounds-check the index")
    void enumOrdinalMapping() {
        String source = mapperFor("""
                    @Enumerated(EnumType.ORDINAL) private Status status;
                    public Status getStatus() { return status; }
                    public void setStatus(Status s) { this.status = s; }
                """, PKG + ".Status", STATUS_ENUM);

        // values() clones its array on every call, so it must be hoisted to a constant.
        assertTrue(source.contains("STATUS_VALUES = Status.values()"), source);
        assertTrue(source.contains("private static final"), source);
        assertTrue(source.contains("out of range"), "missing bounds check:\n" + source);
        assertTrue(source.contains(".ordinal()"), source);
        assertEquals(1, countOccurrences(source, "row.get(\"status\""), source);
    }

    @Test
    void twoFieldsOfTheSameEnumShareOneConstant() {
        String source = mapperFor("""
                    @Enumerated(EnumType.ORDINAL) private Status a;
                    @Enumerated(EnumType.ORDINAL) private Status b;
                    public Status getA() { return a; }
                    public void setA(Status s) { this.a = s; }
                    public Status getB() { return b; }
                    public void setB(Status s) { this.b = s; }
                """, PKG + ".Status", STATUS_ENUM);

        assertEquals(1, countOccurrences(source, "STATUS_VALUES = Status.values()"), source);
    }

    @Test
    @DisplayName("converters become a static constant, not a per-row allocation")
    void converterMapping() {
        String source = mapperFor("""
                    @Convert(UpperConverter.class) private String code;
                    public String getCode() { return code; }
                    public void setCode(String c) { this.code = c; }
                """, PKG + ".UpperConverter", """
                package test.model;
                import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;
                public class UpperConverter implements AttributeConverter<String, String> {
                    public String toCqlColumn(String v) { return v.toUpperCase(); }
                    public String toEntityAttribute(String v) { return v.toLowerCase(); }
                }
                """);

        assertTrue(source.contains("private static final UpperConverter"), source);
        assertTrue(source.contains("new UpperConverter()"), source);
        // Exactly one instantiation: as a constant, never inside map()/toProperties().
        assertEquals(1, countOccurrences(source, "new UpperConverter()"), source);
        assertTrue(source.contains(".toEntityAttribute("), source);
        assertTrue(source.contains(".toCqlColumn("), source);
        assertEquals(1, countOccurrences(source, "row.get(\"code\""), source);
    }

    @Test
    void converterCqlTypeIsTakenFromTheSecondTypeArgument() {
        String source = mapperFor("""
                    @Convert(LongConverter.class) private String code;
                    public String getCode() { return code; }
                    public void setCode(String c) { this.code = c; }
                """, PKG + ".LongConverter", """
                package test.model;
                import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;
                public class LongConverter implements AttributeConverter<String, Long> {
                    public Long toCqlColumn(String v) { return Long.valueOf(v); }
                    public String toEntityAttribute(Long v) { return String.valueOf(v); }
                }
                """);

        assertTrue(source.contains("row.get(\"code\", Long.class)"), source);
    }

    @Test
    @DisplayName("byte[] goes through ByteBuffer without consuming the row's buffer")
    void byteArrayMapping() {
        String source = mapperFor("""
                    private byte[] payload;
                    public byte[] getPayload() { return payload; }
                    public void setPayload(byte[] p) { this.payload = p; }
                """);

        assertTrue(source.contains("row.getByteBuffer(\"payload\")"), source);
        assertTrue(source.contains("ByteBuffer.wrap("), source);
        // duplicate(), or reading the same row twice would hand back an empty array.
        assertTrue(source.contains(".duplicate()"), "missing defensive duplicate():\n" + source);
    }

    @Test
    void anExplicitConverterWinsOverTheByteArrayHandler() {
        // Handler order matters: the annotation-driven handlers are registered first.
        String source = mapperFor("""
                    @Convert(BlobConverter.class) private byte[] payload;
                    public byte[] getPayload() { return payload; }
                    public void setPayload(byte[] p) { this.payload = p; }
                """, PKG + ".BlobConverter", """
                package test.model;
                import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;
                public class BlobConverter implements AttributeConverter<byte[], String> {
                    public String toCqlColumn(byte[] v) { return new String(v); }
                    public byte[] toEntityAttribute(String v) { return v.getBytes(); }
                }
                """);

        assertTrue(source.contains("BlobConverter"), source);
        assertFalse(source.contains("row.getByteBuffer(\"payload\")"),
                "the byte[] handler took precedence over an explicit @Convert:\n" + source);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static void assertEquals(int expected, int actual, String source) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, source);
    }

    @Test
    @DisplayName("two enums sharing a simple name keep separate values() constants")
    void enumsWithTheSameSimpleNameDoNotCollide() {
        // The constant name used to be derived from the simple name, so a.Status and
        // b.Status produced one constant. The mapper deduplicates by name, so the second
        // field silently indexed into the first enum's values() array.
        String source = mapperFor("""
                    @Enumerated(EnumType.ORDINAL) private test.a.Status aStatus;
                    @Enumerated(EnumType.ORDINAL) private test.b.Status bStatus;
                    public test.a.Status getAStatus() { return aStatus; }
                    public void setAStatus(test.a.Status s) { this.aStatus = s; }
                    public test.b.Status getBStatus() { return bStatus; }
                    public void setBStatus(test.b.Status s) { this.bStatus = s; }
                """,
                "test.a.Status", "package test.a;\npublic enum Status { ONE, TWO }\n",
                "test.b.Status", "package test.b;\npublic enum Status { RED, GREEN, BLUE }\n");

        assertTrue(source.contains("TEST_A_STATUS_VALUES"), source);
        assertTrue(source.contains("TEST_B_STATUS_VALUES"), source);
    }

    @Test
    @DisplayName("two converters sharing a simple name keep separate instances")
    void convertersWithTheSameSimpleNameDoNotCollide() {
        String source = mapperFor("""
                    @Convert(test.a.BoxConverter.class) private String a;
                    @Convert(test.b.BoxConverter.class) private String b;
                    public String getA() { return a; }
                    public void setA(String v) { this.a = v; }
                    public String getB() { return b; }
                    public void setB(String v) { this.b = v; }
                """,
                "test.a.BoxConverter", """
                        package test.a;
                        import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;
                        public class BoxConverter implements AttributeConverter<String, String> {
                            public String toCqlColumn(String a) { return a; }
                            public String toEntityAttribute(String d) { return d; }
                        }
                        """,
                "test.b.BoxConverter", """
                        package test.b;
                        import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;
                        public class BoxConverter implements AttributeConverter<String, String> {
                            public String toCqlColumn(String a) { return a; }
                            public String toEntityAttribute(String d) { return d; }
                        }
                        """);

        assertTrue(source.contains("TEST_A_BOXCONVERTER"), source);
        assertTrue(source.contains("TEST_B_BOXCONVERTER"), source);
    }

    @Test
    @DisplayName("a converter inheriting AttributeConverter from a base class resolves its CQL type")
    void converterTypeIsFoundThroughASuperclass() {
        // Only the converter's own interface list used to be searched, so this fell
        // through to row.get(column, Object.class) and failed at runtime with
        // CodecNotFoundException.
        String source = mapperFor("""
                    @Convert(test.conv.LongConverter.class) private String amount;
                    public String getAmount() { return amount; }
                    public void setAmount(String v) { this.amount = v; }
                """,
                "test.conv.BaseConverter", """
                        package test.conv;
                        import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;
                        public abstract class BaseConverter implements AttributeConverter<String, Long> {
                        }
                        """,
                "test.conv.LongConverter", """
                        package test.conv;
                        public class LongConverter extends BaseConverter {
                            public Long toCqlColumn(String a) { return Long.valueOf(a); }
                            public String toEntityAttribute(Long d) { return String.valueOf(d); }
                        }
                        """);

        assertTrue(source.contains("row.get(\"amount\", Long.class)"), source);
    }
}
