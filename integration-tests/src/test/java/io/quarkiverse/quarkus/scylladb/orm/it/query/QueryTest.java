package io.quarkiverse.quarkus.scylladb.orm.it.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Author;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AuthorBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.Book;
import io.quarkiverse.quarkus.scylladb.orm.it.model.BookBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class QueryTest {

    @Inject
    CqlSession session;

    @Inject
    BookBaseRepository bookRepository;

    @Inject
    AuthorBaseRepository authorRepository;

    @BeforeEach
    public void clearDatabase() {
        session.execute("TRUNCATE book");
        session.execute("TRUNCATE author");
    }

    @Test
    @Order(1)
    void testBookExists() {
        createTestBook("Book 1");

        Book byTitle = bookRepository.findByTitle("Book 1");

        assertNotNull(byTitle);
        assertEquals("Book 1", byTitle.getTitle());
    }

    @Test
    @Order(2)
    void testChangeBookExists() {
        UUID testBook = createTestBook("Book 1");

        Book book = bookRepository.touchAndReturn(testBook);

        assertNotNull(book);
    }

    @Test
    @Order(3)
    void testFindAllByTitle() {
        createTestBook("SameTitle");
        createTestBook("SameTitle");
        createTestBook("DifferentTitle");

        List<Book> results = bookRepository.findAllByTitle("SameTitle");

        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(b -> "SameTitle".equals(b.getTitle())));
    }

    @Test
    @Order(4)
    void testFindAllByTitleEmpty() {
        createTestBook("Existing");

        List<Book> results = bookRepository.findAllByTitle("NonExistent");

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @Order(5)
    void testFindByTitleNotFound() {
        Book byTitle = bookRepository.findByTitle("NoSuchBook");

        assertNull(byTitle);
    }

    @Test
    @Order(6)
    void testDeleteAll() {
        createTestBook("ToDelete1");
        createTestBook("ToDelete2");

        List<Book> before = bookRepository.findAllByTitle("ToDelete1");
        assertFalse(before.isEmpty());

        bookRepository.deleteAll();

        List<Book> after = bookRepository.findAll();
        assertTrue(after.isEmpty());
    }

    @Test
    @Order(7)
    void testSaveAndFindById() {
        Book book = new Book();
        book.setId(UUID.randomUUID());
        book.setTitle("SaveTest");
        book.setActive(true);

        Book saved = bookRepository.save(book);
        assertNotNull(saved);

        Book found = bookRepository.findById(saved.getId());
        assertNotNull(found);
        assertEquals("SaveTest", found.getTitle());
        assertEquals(true, found.getActive());
    }

    @Test
    @Order(8)
    void testUpdateBook() {
        UUID id = createTestBook("Original");

        Book book = bookRepository.findById(id);
        assertNotNull(book);
        book.setTitle("Modified");

        Book updated = bookRepository.update(book);
        assertEquals("Modified", updated.getTitle());
    }

    @Test
    @Order(9)
    void testPrefixParamsTwoArgumentsBlocking() {
        UUID id = UUID.randomUUID();
        createTestAuthor(id, "Ada");

        Author found = authorRepository.findByIdAndNamePrefix(id, "Ada");

        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals("Ada", found.getName());
    }

    @Test
    @Order(10)
    void testPrefixParamsThreeArgumentsBlocking() {
        UUID id = UUID.randomUUID();
        createTestAuthor(id, "Linus");

        Author found = authorRepository.findByThreeParamsPrefix(id, "Linus", "Linus");

        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals("Linus", found.getName());
    }

    private UUID createTestBook(String title) {
        UUID bookId = UUID.randomUUID();

        session.execute(SimpleStatement.builder(
                "INSERT INTO book (id, title, active) VALUES (?, ?, ?)")
                .addPositionalValue(bookId)
                .addPositionalValue(title)
                .addPositionalValue(false)
                .build());

        return bookId;
    }

    private void createTestAuthor(UUID id, String name) {
        session.execute(SimpleStatement.builder(
                "INSERT INTO author (id, name) VALUES (?, ?)")
                .addPositionalValue(id)
                .addPositionalValue(name)
                .build());
    }
}
