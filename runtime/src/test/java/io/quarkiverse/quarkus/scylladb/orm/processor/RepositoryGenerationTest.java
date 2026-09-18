package io.quarkiverse.quarkus.scylladb.orm.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the repository generators and the {@code @Query} diagnostics. The error paths
 * in particular could not be tested through the integration tests at all: a build-time
 * error there would break the build rather than assert anything.
 */
class RepositoryGenerationTest {

    private static final String PKG = "test.model";

    private static GeneratedSources.Result compile(String annotations, String body) {
        return GeneratedSources.compile(PKG + ".Sample", """
                package test.model;

                import java.util.*;
                import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                import io.quarkiverse.quarkus.scylladb.orm.enums.*;

                %s
                public class Sample {
                    @PartitionKey private UUID id;
                    private String name;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String n) { this.name = n; }
                %s
                }
                """.formatted(annotations, body));
    }

    @Nested
    @DisplayName("repository shape")
    class Shape {

        @Test
        void theIdTypeIsThePartitionKeyType() {
            GeneratedSources.Result result = compile("@Table(\"sample\")", "");

            assertTrue(result.success(), result.errorText());
            assertTrue(result.source(PKG + ".SampleBaseRepository").contains("Repository<Sample, UUID>"),
                    result.source(PKG + ".SampleBaseRepository"));
        }

        @Test
        void aCompositePartitionKeyFallsBackToObject() {
            GeneratedSources.Result result = GeneratedSources.compile(PKG + ".Sample", """
                    package test.model;
                    import java.util.UUID;
                    import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                    @Table("sample")
                    public class Sample {
                        @PartitionKey(ordinal = 0) private String tenant;
                        @PartitionKey(ordinal = 1) private UUID id;
                        public String getTenant() { return tenant; }
                        public void setTenant(String t) { this.tenant = t; }
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    }
                    """);

            assertTrue(result.success(), result.errorText());
            assertTrue(result.source(PKG + ".SampleBaseRepository").contains("Repository<Sample, Object>"),
                    "findById cannot work with a composite partition key, so ID must stay Object");
        }

        @Test
        void theKeyspaceIsPrefixedOntoTheTableName() {
            GeneratedSources.Result result = compile("@Table(value = \"sample\", keyspace = \"orders\")", "");

            assertTrue(result.success(), result.errorText());
            assertTrue(result.source(PKG + ".SampleBaseRepository").contains("\"orders.sample\""),
                    result.source(PKG + ".SampleBaseRepository"));
        }

        @Test
        void generationModeSelectsWhichRepositoriesAppear() {
            GeneratedSources.Result blocking = compile(
                    "@Table(\"sample\") @GenerateRepository(GenerateRepository.RepositoryType.BLOCKING)", "");
            GeneratedSources.Result reactive = compile(
                    "@Table(\"sample\") @GenerateRepository(GenerateRepository.RepositoryType.REACTIVE)", "");

            assertTrue(blocking.sources().containsKey(PKG + ".SampleBaseRepository"));
            assertFalse(blocking.sources().containsKey(PKG + ".SampleBaseReactiveRepository"));
            assertFalse(reactive.sources().containsKey(PKG + ".SampleBaseRepository"));
            assertTrue(reactive.sources().containsKey(PKG + ".SampleBaseReactiveRepository"));
        }

        @Test
        void theNoArgConstructorIsProtectedRatherThanPublic() {
            // It exists only so CDI can build a client proxy; an instance created through
            // it has no session and NPEs on every call.
            GeneratedSources.Result result = compile("@Table(\"sample\")", "");

            assertTrue(result.source(PKG + ".SampleBaseRepository").contains("protected SampleBaseRepository()"),
                    result.source(PKG + ".SampleBaseRepository"));
        }
    }

    @Nested
    @DisplayName("@Query select lists")
    class SelectLists {

        @Test
        void wildcardIsExpandedToTheEntityColumns() {
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "all", cql = "SELECT * FROM sample", returnType = ReturnType.LIST))
                    """, "");

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("SELECT id, name FROM sample"), repo);
            assertFalse(repo.contains("SELECT * FROM sample"), repo);
        }

        @Test
        void aPartialSelectListIsRejectedAtBuildTime() {
            // Used to compile and then fail at runtime with
            // "name is not a column in this row".
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "partial", cql = "SELECT id FROM sample", returnType = ReturnType.LIST))
                    """, "");

            assertFalse(result.success());
            assertTrue(result.errorText().contains("partial"), result.errorText());
            assertTrue(result.errorText().contains("name"), result.errorText());
        }

        @Test
        void aProjectionMaySelectASubset() {
            GeneratedSources.Result result = GeneratedSources.compile(java.util.Map.of(
                    PKG + ".NameOnly", """
                            package test.model;
                            public record NameOnly(String name) {}
                            """,
                    PKG + ".Sample", """
                            package test.model;
                            import java.util.UUID;
                            import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                            import io.quarkiverse.quarkus.scylladb.orm.enums.*;
                            @Table("sample")
                            @Queries(@Query(name = "names", cql = "SELECT name FROM sample",
                                    returnType = ReturnType.LIST, resultClass = NameOnly.class))
                            public class Sample {
                                @PartitionKey private UUID id;
                                private String name;
                                public UUID getId() { return id; }
                                public void setId(UUID id) { this.id = id; }
                                public String getName() { return name; }
                                public void setName(String n) { this.name = n; }
                            }
                            """));

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("SELECT name FROM sample"), repo);
            assertTrue(repo.contains("row.getString(\"name\")"), repo);
        }
    }

    @Nested
    @DisplayName("@Query diagnostics")
    class Diagnostics {

        @Test
        void ddlIsRejectedUnlessExplicitlyAllowed() {
            GeneratedSources.Result rejected = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "wipe", cql = "TRUNCATE sample", returnType = ReturnType.VOID))
                    """, "");

            assertFalse(rejected.success());
            assertTrue(rejected.errorText().contains("allowSchemaChanges"), rejected.errorText());

            GeneratedSources.Result allowed = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "wipe", cql = "TRUNCATE sample", returnType = ReturnType.VOID,
                            allowSchemaChanges = true))
                    """, "");

            assertTrue(allowed.success(), allowed.errorText());
        }

        @Test
        void aWordInAStringLiteralDoesNotTripTheDdlGuard() {
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "byAction", cql = "SELECT * FROM sample WHERE name = 'DROP' ALLOW FILTERING",
                            returnType = ReturnType.LIST))
                    """, "");

            assertTrue(result.success(), result.errorText());
        }

        @Test
        void duplicateQueryNamesAreReported() {
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries({
                        @Query(name = "dup", cql = "SELECT * FROM sample", returnType = ReturnType.LIST),
                        @Query(name = "dup", cql = "SELECT * FROM sample", returnType = ReturnType.LIST)
                    })
                    """, "");

            assertFalse(result.success());
            assertTrue(result.errorText().contains("Duplicate @Query method name"), result.errorText());
        }

        @Test
        void aBlankCqlIsReported() {
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "empty", cql = "  ", returnType = ReturnType.LIST))
                    """, "");

            assertFalse(result.success());
            assertTrue(result.errorText().contains("must not be blank"), result.errorText());
        }

        @Test
        void aRepeatedNamedParameterBecomesOneMethodParameter() {
            // "WHERE a >= :ts AND b >= :ts" emitted byRange(Object ts, Object ts) - a
            // signature javac rejects, with an error pointing at generated code rather
            // than at the @Query. One parameter, two markers, the value bound twice.
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "byRange",
                            cql = "SELECT * FROM sample WHERE seen >= :ts AND touched >= :ts ALLOW FILTERING",
                            returnType = ReturnType.LIST,
                            paramTypes = @Query.Param(name = "ts", type = String.class)))
                    """, "");

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("byRange(String ts)"), repo);
            assertTrue(repo.contains("query(query, ts, ts)"), repo);
        }

        @Test
        void aRepeatedStructuralParameterIsAlsoDeclaredOnce() {
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "twice",
                            cql = "SELECT * FROM sample WHERE name = :name LIMIT :limit /* :limit */",
                            returnType = ReturnType.LIST))
                    """, "");

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("twice(String name, Integer limit)"), repo);
            // One declaration, but still one String.format argument per placeholder.
            assertTrue(
                    repo.contains(
                            "String.format(\"SELECT id, name FROM sample WHERE name = ? LIMIT %s /* %s */\", limit, limit)"),
                    repo);
        }

        @Test
        void aProjectionCanReadACollectionColumn() {
            // ClassName.bestGuess("java.util.List<java.lang.String>") threw, and the
            // whole @Query was dropped with "not a valid name: List<java".
            GeneratedSources.Result result = GeneratedSources.compile(java.util.Map.of(
                    PKG + ".Tagged", """
                            package test.model;
                            import java.util.*;
                            public record Tagged(UUID id, List<String> tags, Map<String, Integer> counts) {}
                            """,
                    PKG + ".Sample", """
                            package test.model;
                            import java.util.*;
                            import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                            import io.quarkiverse.quarkus.scylladb.orm.enums.*;
                            @Table("sample")
                            @Queries(@Query(name = "tagsOf", cql = "SELECT id, tags, counts FROM sample WHERE id = :id",
                                    resultClass = Tagged.class, returnType = ReturnType.SINGLE))
                            public class Sample {
                                @PartitionKey private UUID id;
                                private List<String> tags;
                                private Map<String, Integer> counts;
                                public UUID getId() { return id; }
                                public void setId(UUID id) { this.id = id; }
                                public List<String> getTags() { return tags; }
                                public void setTags(List<String> t) { this.tags = t; }
                                public Map<String, Integer> getCounts() { return counts; }
                                public void setCounts(Map<String, Integer> c) { this.counts = c; }
                            }
                            """));

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("row.getList(\"tags\", String.class)"), repo);
            assertTrue(repo.contains("row.getMap(\"counts\", String.class, Integer.class)"), repo);
        }

        @Test
        void anUnsupportedGenericProjectionFieldNamesTheQueryAndTheColumn() {
            GeneratedSources.Result result = GeneratedSources.compile(java.util.Map.of(
                    PKG + ".Boxed", """
                            package test.model;
                            import java.util.*;
                            public record Boxed(UUID id, Optional<String> name) {}
                            """,
                    PKG + ".Sample", """
                            package test.model;
                            import java.util.*;
                            import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                            import io.quarkiverse.quarkus.scylladb.orm.enums.*;
                            @Table("sample")
                            @Queries(@Query(name = "boxed", cql = "SELECT id, name FROM sample WHERE id = :id",
                                    resultClass = Boxed.class, returnType = ReturnType.SINGLE))
                            public class Sample {
                                @PartitionKey private UUID id;
                                private String name;
                                public UUID getId() { return id; }
                                public void setId(UUID id) { this.id = id; }
                                public String getName() { return name; }
                                public void setName(String n) { this.name = n; }
                            }
                            """));

            assertFalse(result.success());
            assertTrue(result.errorText().contains("boxed"), result.errorText());
            assertTrue(result.errorText().contains("name"), result.errorText());
            assertTrue(result.errorText().contains("java.util.Optional"), result.errorText());
        }

        @Test
        void aNestedGenericProjectionFieldIsRejectedRatherThanMiscompiled() {
            // getList takes a Class, so List<List<String>> has nothing to pass — emitting
            // "java.util.List<java.lang.String>.class" would move the failure into
            // generated code.
            GeneratedSources.Result result = GeneratedSources.compile(java.util.Map.of(
                    PKG + ".Nested", """
                            package test.model;
                            import java.util.*;
                            public record Nested(UUID id, List<List<String>> rows) {}
                            """,
                    PKG + ".Sample", """
                            package test.model;
                            import java.util.*;
                            import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                            import io.quarkiverse.quarkus.scylladb.orm.enums.*;
                            @Table("sample")
                            @Queries(@Query(name = "nested", cql = "SELECT id, rows FROM sample WHERE id = :id",
                                    resultClass = Nested.class, returnType = ReturnType.SINGLE))
                            public class Sample {
                                @PartitionKey private UUID id;
                                private String name;
                                public UUID getId() { return id; }
                                public void setId(UUID id) { this.id = id; }
                                public String getName() { return name; }
                                public void setName(String n) { this.name = n; }
                            }
                            """));

            assertFalse(result.success());
            assertTrue(result.errorText().contains("nested"), result.errorText());
            assertTrue(result.errorText().contains("rows"), result.errorText());
        }

        @Test
        void limitOneDowngradesAListToASingleResult() {
            GeneratedSources.Result result = compile("""
                    @Table("sample")
                    @Queries(@Query(name = "first", cql = "SELECT * FROM sample LIMIT 1", returnType = ReturnType.LIST))
                    """, "");

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("public Sample first()"), repo);
            assertTrue(repo.contains("querySingle("), repo);
        }
    }

    @Nested
    @DisplayName("parameter binding")
    class ParameterBinding {

        @Test
        void aColumnNamedSortCanBeQueriedWithAnExplicitBoundBinding() {
            // Without the override :sort was interpolated and then rejected by the
            // "column ASC/DESC" format check, so the column could not be queried at all.
            GeneratedSources.Result result = GeneratedSources.compile(PKG + ".Sample", """
                    package test.model;
                    import java.util.UUID;
                    import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                    import io.quarkiverse.quarkus.scylladb.orm.enums.*;

                    @Table("sample")
                    @Queries(@Query(
                        name = "bySort",
                        cql = "SELECT id, sort FROM sample WHERE sort = :sort",
                        returnType = ReturnType.LIST,
                        paramTypes = @Query.Param(
                            name = "sort", type = String.class, binding = Query.Binding.BOUND)))
                    public class Sample {
                        @PartitionKey private UUID id;
                        private String sort;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public String getSort() { return sort; }
                        public void setSort(String s) { this.sort = s; }
                    }
                    """);

            assertTrue(result.success(), result.errorText());
            String repo = result.source(PKG + ".SampleBaseRepository");
            assertTrue(repo.contains("WHERE sort = ?"), repo);
            assertFalse(repo.contains("String.format"), "a bound parameter must not be interpolated:\n" + repo);
        }

        @Test
        void sortIsStillInterpolatedWithoutAnOverride() {
            GeneratedSources.Result result = compile(
                    """
                            @Table("sample")
                            @Queries(@Query(name = "sorted",                             cql = "SELECT id, name FROM sample WHERE id = :id ORDER BY :sort",                             returnType = ReturnType.LIST))
                            """,
                    "");

            assertTrue(result.success(), result.errorText());
            assertTrue(result.source(PKG + ".SampleBaseRepository").contains("String.format"),
                    result.source(PKG + ".SampleBaseRepository"));
        }

        @Test
        void aStructuralOffsetIsRejectedBecauseCqlHasNone() {
            GeneratedSources.Result result = compile(
                    """
                            @Table("sample")
                            @Queries(@Query(name = "paged",                             cql = "SELECT id, name FROM sample WHERE id = :id LIMIT :limit OFFSET :offset",                             returnType = ReturnType.LIST))
                            """,
                    "");

            assertFalse(result.success());
            assertTrue(result.errorText().contains("OFFSET"), result.errorText());
        }
    }
}
