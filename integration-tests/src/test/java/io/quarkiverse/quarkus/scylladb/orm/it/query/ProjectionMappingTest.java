package io.quarkiverse.quarkus.scylladb.orm.it.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import io.quarkiverse.quarkus.scylladb.orm.it.model.BookBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.BookSummary;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProjectionMappingTest {

    @Inject
    CqlSession session;

    @Inject
    BookBaseRepository bookRepository;

    @BeforeEach
    public void clearDatabase() {
        session.execute("TRUNCATE book");
    }

    @Test
    @Order(1)
    void testSingleProjectionReturnsBookSummary() {
        createTestBook("Projection Test", true);

        BookSummary summary = bookRepository.getBookSummary("Projection Test");

        assertNotNull(summary);
        assertEquals("Projection Test", summary.title());
        assertEquals(true, summary.active());
    }

    @Test
    @Order(2)
    void testSingleProjectionWithInactiveBook() {
        createTestBook("Inactive Book", false);

        BookSummary summary = bookRepository.getBookSummary("Inactive Book");

        assertNotNull(summary);
        assertEquals("Inactive Book", summary.title());
        assertEquals(false, summary.active());
    }

    @Test
    @Order(3)
    void testSingleProjectionReturnsNullForMissingBook() {
        BookSummary summary = bookRepository.getBookSummary("NonExistent");

        assertNull(summary);
    }

    @Test
    @Order(4)
    void testListProjectionReturnsAllBooks() {
        createTestBook("Book A", true);
        createTestBook("Book B", false);
        createTestBook("Book C", true);

        List<BookSummary> summaries = bookRepository.getAllBookSummaries();

        assertNotNull(summaries);
        assertEquals(3, summaries.size());

        long activeCount = summaries.stream().filter(s -> Boolean.TRUE.equals(s.active())).count();
        assertEquals(2, activeCount);
    }

    @Test
    @Order(5)
    void testListProjectionReturnsEmptyListForNoBooks() {
        List<BookSummary> summaries = bookRepository.getAllBookSummaries();

        assertNotNull(summaries);
        assertTrue(summaries.isEmpty());
    }

    private UUID createTestBook(String title, boolean active) {
        UUID bookId = UUID.randomUUID();

        session.execute(SimpleStatement.builder(
                "INSERT INTO book (id, title, active) VALUES (?, ?, ?)")
                .addPositionalValue(bookId)
                .addPositionalValue(title)
                .addPositionalValue(active)
                .build());

        return bookId;
    }
}
