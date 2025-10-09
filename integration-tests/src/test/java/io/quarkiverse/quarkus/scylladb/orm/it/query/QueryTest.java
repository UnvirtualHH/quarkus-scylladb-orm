package io.quarkiverse.quarkus.scylladb.orm.it.query;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Book;
import io.quarkiverse.quarkus.scylladb.orm.it.model.BookBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class QueryTest {

    @Inject
    CqlSession session;

    @Inject
    BookBaseRepository bookRepository;

    @BeforeEach
    public void clearDatabase() {
        session.execute("TRUNCATE book");
        session.execute("TRUNCATE author");
    }

    @Test
    void testBookExists() {
        createTestBook();

        Book byTitle = bookRepository.findByTitle("Book 1");

        assertNotNull(byTitle);
    }

    @Test
    void testChangeBookExists() {
        UUID testBook = createTestBook();

        Book book = bookRepository.touchAndReturn(testBook);

        assertNotNull(book);
    }

    private UUID createTestBook() {
        UUID bookId = UUID.randomUUID();

        session.execute(SimpleStatement.builder(
                "INSERT INTO book (id, title, active) VALUES (?, ?, ?)")
                .addPositionalValue(bookId)
                .addPositionalValue("Book 1")
                .addPositionalValue(false)
                .build());

        return bookId;
    }
}
