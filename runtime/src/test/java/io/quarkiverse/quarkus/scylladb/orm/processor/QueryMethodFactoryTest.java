package io.quarkiverse.quarkus.scylladb.orm.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The CQL handling in {@link QueryMethodFactory} is ~750 lines of regex and string
 * manipulation that emits Java source. It had no direct tests: everything was covered
 * only indirectly, through a handful of {@code @Query} examples on the integration-test
 * entities, which needs a Docker daemon and a running ScyllaDB to say anything at all.
 * These run in milliseconds and cover the edges those examples never reach.
 */
class QueryMethodFactoryTest {

    @Nested
    @DisplayName("parameter names")
    class ParameterNames {

        @Test
        void extractsNamedParametersInOrder() {
            assertEquals(List.of("tenant", "deviceId"),
                    QueryMethodFactory.extractParamNames(
                            "SELECT * FROM event WHERE tenant = :tenant AND device_id = :deviceId"));
        }

        @Test
        void doesNotConfuseAPrefixWithALongerName() {
            // :id must not swallow :id2 / :id3 - that was the point of the token-safe
            // replacement, and nothing tested it directly.
            assertEquals(List.of("id", "id2", "id3"),
                    QueryMethodFactory.extractParamNames(
                            "SELECT * FROM author WHERE id = :id AND a = :id2 AND b = :id3"));
        }

        @Test
        void guessesFromColumnNamesWhenOnlyPositionalMarkersArePresent() {
            assertEquals(List.of("tenant", "deviceId"),
                    QueryMethodFactory.extractParamNames(
                            "SELECT * FROM event WHERE tenant = ? AND device_id = ?"));
        }

        @Test
        void fillsInGenericNamesForUnattributableMarkers() {
            assertEquals(List.of("param1", "param2"),
                    QueryMethodFactory.extractParamNames("INSERT INTO t (a, b) VALUES (?, ?)"));
        }

        @Test
        void namesATrailingLimitMarker() {
            List<String> names = QueryMethodFactory.extractParamNames(
                    "SELECT * FROM person WHERE name = ? LIMIT ?");

            assertEquals(List.of("name", "limit"), names);
        }
    }

    @Nested
    @DisplayName("parameter names must be usable in a method signature")
    class ParameterNameSanitising {

        @Test
        void repeatedColumnsGetDistinctNames() {
            // Regression: "WHERE a = ? AND a = ?" produced two parameters called "a",
            // so the generated method did not compile.
            assertEquals(List.of("a", "a2"),
                    QueryMethodFactory.extractParamNames("SELECT * FROM t WHERE a = ? AND a = ?"));
        }

        @Test
        void threeRepeatsKeepCounting() {
            assertEquals(List.of("a", "a2", "a3"),
                    QueryMethodFactory.makeValidJavaParamNames(List.of("a", "a", "a")));
        }

        @Test
        void javaKeywordsAreEscaped() {
            // A column literally called "class" or "new" is legal in CQL.
            assertEquals(List.of("classParam", "newParam"),
                    QueryMethodFactory.makeValidJavaParamNames(List.of("class", "new")));
        }

        @Test
        void collisionWithTheGeneratedLocalVariableIsAvoided() {
            // The generated body declares `String query = ...`; a parameter of the same
            // name would not compile.
            assertEquals(List.of("queryParam"),
                    QueryMethodFactory.makeValidJavaParamNames(List.of(QueryMethodFactory.QUERY_LOCAL_VARIABLE)));
        }

        @Test
        void escapedNamesStillGetDeduplicated() {
            assertEquals(List.of("classParam", "classParam2"),
                    QueryMethodFactory.makeValidJavaParamNames(List.of("class", "class")));
        }
    }

    @Nested
    @DisplayName("column name to camel case")
    class CamelCase {

        @Test
        void convertsSnakeCase() {
            assertEquals("deviceId", QueryMethodFactory.toCamelCase("device_id"));
            assertEquals("timeOfDay", QueryMethodFactory.toCamelCase("time_of_day"));
        }

        @Test
        void leavesASingleWordAlone() {
            assertEquals("tenant", QueryMethodFactory.toCamelCase("tenant"));
        }

        @Test
        void toleratesDoubleUnderscoresAndTrailingSeparators() {
            assertEquals("aB", QueryMethodFactory.toCamelCase("a__b"));
            assertEquals("a", QueryMethodFactory.toCamelCase("a_"));
        }
    }

    @Nested
    @DisplayName("statement classification")
    class Classification {

        @Test
        void recognisesTheLeadingVerb() {
            assertTrue(QueryMethodFactory.isSelect("SELECT * FROM t"));
            assertTrue(QueryMethodFactory.isSelect("  select * from t"));
            assertTrue(QueryMethodFactory.isWrite("INSERT INTO t (a) VALUES (?)"));
            assertTrue(QueryMethodFactory.isWrite("DELETE FROM t WHERE a = ?"));
            assertTrue(QueryMethodFactory.isSchema("CREATE TABLE t (a int PRIMARY KEY)"));
        }

        @Test
        void doesNotClassifyByWordsInsideStringLiterals() {
            // The DDL guard was anchored to the leading keyword but its siblings were
            // not, so a literal containing the word decided the generated method body.
            assertFalse(QueryMethodFactory.isWrite("SELECT * FROM t WHERE action = 'DELETE'"));
            assertFalse(QueryMethodFactory.isSchema("SELECT * FROM t WHERE action = 'DROP'"));
        }

        @Test
        void recognisesLightweightTransactions() {
            assertTrue(QueryMethodFactory.isConditional("UPDATE t SET a = 1 WHERE id = ? IF EXISTS"));
            assertTrue(QueryMethodFactory.isConditional("DELETE FROM t WHERE id = ? IF NOT EXISTS"));
            assertTrue(QueryMethodFactory.isConditional("UPDATE t SET a = 1 WHERE id = ? IF version = 3"));
        }

        @Test
        void doesNotSeeAConditionInAStringLiteral() {
            // Previously a bare " IF " substring search, so this was misclassified as an
            // LWT and got the wrong method body.
            assertFalse(QueryMethodFactory.isConditional("UPDATE t SET note = 'if you like' WHERE id = ?"));
        }

        @Test
        void returningWinsOverConditional() {
            assertFalse(QueryMethodFactory.isConditional("UPDATE t SET a = 1 IF EXISTS RETURNING *"));
            assertTrue(QueryMethodFactory.isReturning("UPDATE t SET a = 1 RETURNING *"));
        }
    }

    @Nested
    @DisplayName("select list vs. entity columns")
    class SelectList {

        private static final List<String> COLUMNS = List.of("id", "street", "housenumber");

        private static String align(String cql) {
            return QueryMethodFactory.alignSelectListWithEntityColumns(cql, COLUMNS, "someQuery");
        }

        @Test
        void wildcardIsExpandedToTheEntityColumns() {
            assertEquals("SELECT id, street, housenumber FROM address WHERE id = :id",
                    align("SELECT * FROM address WHERE id = :id"));
        }

        @Test
        void wildcardExpansionKeepsDistinctAndTheTail() {
            assertEquals("SELECT DISTINCT id, street, housenumber FROM address ALLOW FILTERING",
                    align("SELECT DISTINCT * FROM address ALLOW FILTERING"));
        }

        @Test
        void aCompleteExplicitListIsLeftAlone() {
            String cql = "SELECT id, street, housenumber FROM address";
            assertEquals(cql, align(cql));
        }

        @Test
        void columnOrderAndCaseDoNotMatter() {
            String cql = "SELECT HOUSENUMBER, Id, street FROM address";
            assertEquals(cql, align(cql));
        }

        @Test
        void aPartialListIsRejectedAtBuildTime() {
            // This used to compile and then fail at runtime with
            // "street is not a column in this row".
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> align("SELECT id FROM address WHERE id = :id"));

            assertTrue(error.getMessage().contains("someQuery"), error.getMessage());
            assertTrue(error.getMessage().contains("street"), error.getMessage());
            assertTrue(error.getMessage().contains("resultClass"), error.getMessage());
        }

        @Test
        void functionsAndAliasesAreLeftToTheDatabase() {
            // Judging these would risk turning a working query into a build failure.
            String withFunction = "SELECT writetime(street) FROM address";
            String withAlias = "SELECT id AS the_id FROM address";

            assertEquals(withFunction, align(withFunction));
            assertEquals(withAlias, align(withAlias));
        }

        @Test
        void nonSelectStatementsPassThrough() {
            String cql = "UPDATE address SET street = :street WHERE id = :id";
            assertEquals(cql, align(cql));
        }
    }

    @Nested
    @DisplayName("structural parameters")
    class StructuralParameters {

        @Test
        void areRecognisedCaseInsensitively() {
            assertTrue(QueryMethodFactory.isStructuralParam("limit"));
            assertTrue(QueryMethodFactory.isStructuralParam("ORDER"));
            assertTrue(QueryMethodFactory.isStructuralParam("Sort"));
            assertFalse(QueryMethodFactory.isStructuralParam("tenant"));
        }

        @Test
        void becomeFormatPlaceholdersWhileBindableOnesSurvive() {
            String result = QueryMethodFactory.replaceStructuralParamsWithFormat(
                    "SELECT * FROM t WHERE a = :a LIMIT :limit", Set.of("limit"));

            assertEquals("SELECT * FROM t WHERE a = :a LIMIT %s", result);
        }

        @Test
        void literalPercentIsEscapedSoItSurvivesStringFormat() {
            String result = QueryMethodFactory.replaceStructuralParamsWithFormat(
                    "SELECT * FROM t WHERE a LIKE '50%' LIMIT :limit", Set.of("limit"));

            assertEquals("SELECT * FROM t WHERE a LIKE '50%%' LIMIT %s", result);
        }

        @Test
        void bindableParametersBecomePositionalMarkers() {
            String result = QueryMethodFactory.replaceBindableParamsWithPositional(
                    "SELECT * FROM t WHERE a = :a AND b = :b", List.of("a", "b"));

            assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", result);
        }

        @Test
        void replacementIsTokenSafeAcrossPrefixes() {
            String result = QueryMethodFactory.replaceBindableParamsWithPositional(
                    "SELECT * FROM t WHERE a = :id AND b = :id2", List.of("id", "id2"));

            assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", result);
        }

        @Test
        void leavesUnlistedParametersUntouched() {
            String result = QueryMethodFactory.replaceBindableParamsWithPositional(
                    "SELECT * FROM t WHERE a = :a LIMIT :limit", List.of("a"));

            assertEquals("SELECT * FROM t WHERE a = ? LIMIT :limit", result);
        }
    }
}
