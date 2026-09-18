package io.quarkiverse.quarkus.scylladb.orm.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The entity shape checks and the generator guards, which all report at build time.
 * <p>
 * Every case here used to compile cleanly and then fail somewhere else: in the driver, in
 * generated source, or — for the duplicate column and the duplicate ordinal — not at all,
 * quietly writing to the wrong column. What matters is that the diagnostic names the
 * entity's own mistake, so each test asserts on the message rather than only on failure.
 */
class EntityValidationTest {

    private static final String PKG = "test.model";

    private static GeneratedSources.Result compile(String body) {
        return GeneratedSources.compile(PKG + ".Sample", """
                package test.model;

                import java.util.*;
                import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                import io.quarkiverse.quarkus.scylladb.orm.enums.*;

                @Table("sample")
                public class Sample {
                %s
                }
                """.formatted(body));
    }

    @Nested
    @DisplayName("entity shape")
    class Shape {

        @Test
        void anEntityWithoutAPartitionKeyIsRejected() {
            // Previously generated fine and then threw ArrayIndexOutOfBoundsException out
            // of exists() on the first call, naming neither the entity nor the annotation.
            GeneratedSources.Result result = compile("""
                        private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("@PartitionKey"), result.errorText());
        }

        @Test
        void twoFieldsOnTheSameColumnAreRejected() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey private UUID id;
                        private String name;
                        @Column("name") private String alias;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public String getName() { return name; }
                        public void setName(String n) { this.name = n; }
                        public String getAlias() { return alias; }
                        public void setAlias(String a) { this.alias = a; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("name"), result.errorText());
            assertTrue(result.errorText().contains("more than one field"), result.errorText());
        }

        @Test
        void aShadowedInheritedFieldIsRejectedAsADuplicateColumn() {
            // allFields() walks the hierarchy, so a subclass field of the same name used
            // to produce INSERT INTO t (id, ..., id, ...).
            GeneratedSources.Result result = GeneratedSources.compile(PKG + ".Sample", """
                    package test.model;
                    import java.util.UUID;
                    import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

                    class Base {
                        protected String name;
                        public String getName() { return name; }
                        public void setName(String n) { this.name = n; }
                    }

                    @Table("sample")
                    public class Sample extends Base {
                        @PartitionKey private UUID id;
                        private String name;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("more than one field"), result.errorText());
        }

        @Test
        void duplicateKeyOrdinalsAreRejected() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey(ordinal = 0) private String tenant;
                        @PartitionKey(ordinal = 0) private UUID id;
                        public String getTenant() { return tenant; }
                        public void setTenant(String t) { this.tenant = t; }
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("ordinal 0"), result.errorText());
        }

        @Test
        void distinctOrdinalsAreFine() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey(ordinal = 0) private String tenant;
                        @PartitionKey(ordinal = 1) private UUID id;
                        public String getTenant() { return tenant; }
                        public void setTenant(String t) { this.tenant = t; }
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    """);

            assertTrue(result.success(), result.errorText());
        }

        @Test
        void aTransientKeyIsRejected() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey @Transient private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("@Transient"), result.errorText());
        }

        @Test
        void aFieldThatIsBothKindsOfKeyIsRejected() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey @ClusteringKey private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("@ClusteringKey"), result.errorText());
        }
    }

    @Nested
    @DisplayName("collection columns")
    class Collections {

        @Test
        void aRawCollectionIsRejectedWithAMessageNamingTheField() {
            // Used to throw IndexOutOfBoundsException straight out of the processor, which
            // javac reports as "An annotation processor threw an uncaught exception".
            GeneratedSources.Result result = compile("""
                        @PartitionKey private UUID id;
                        private List tags;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public List getTags() { return tags; }
                        public void setTags(List t) { this.tags = t; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("tags"), result.errorText());
        }

        @Test
        void aNestedGenericCollectionIsRejected() {
            // Emitted `row.getList("rows", List<String>.class)`, so the failure landed in
            // generated source with no hint about the field that produced it.
            GeneratedSources.Result result = compile("""
                        @PartitionKey private UUID id;
                        private List<List<String>> rows;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public List<List<String>> getRows() { return rows; }
                        public void setRows(List<List<String>> r) { this.rows = r; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("rows"), result.errorText());
        }

        @Test
        void aWildcardCollectionIsRejected() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey private UUID id;
                        private List<? extends CharSequence> tags;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public List<? extends CharSequence> getTags() { return tags; }
                        public void setTags(List<? extends CharSequence> t) { this.tags = t; }
                    """);

            assertFalse(result.success());
            assertTrue(result.errorText().contains("tags"), result.errorText());
        }

        @Test
        void concreteElementTypesStillWork() {
            GeneratedSources.Result result = compile("""
                        @PartitionKey private UUID id;
                        private List<String> tags;
                        private Map<String, Integer> counts;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public List<String> getTags() { return tags; }
                        public void setTags(List<String> t) { this.tags = t; }
                        public Map<String, Integer> getCounts() { return counts; }
                        public void setCounts(Map<String, Integer> c) { this.counts = c; }
                    """);

            assertTrue(result.success(), result.errorText());
            assertTrue(result.source(PKG + ".SampleMapper").contains("row.getList(\"tags\", String.class)"));
            assertTrue(result.source(PKG + ".SampleMapper")
                    .contains("row.getMap(\"counts\", String.class, Integer.class)"));
        }
    }
}
