package io.quarkiverse.quarkus.scylladb.orm.it.person;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Person;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Pageable;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Paged;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonRepositoryTest {

    @Inject
    PersonBaseRepository personRepository;

    private static UUID createdId;
    private static UUID secondId;

    @Test
    @Order(1)
    void testCreate() {
        Person person = new Person();
        person.setId(UUID.randomUUID());
        person.setName("Tester");
        person.setAddressId(UUID.randomUUID());

        Person created = personRepository.save(person);
        assertNotNull(created.getId());
        assertEquals("Tester", created.getName());

        createdId = created.getId();
    }

    @Test
    @Order(2)
    void testCreateSecond() {
        Person person = new Person();
        person.setId(UUID.randomUUID());
        person.setName("SecondPerson");
        person.setAddressId(UUID.randomUUID());

        Person created = personRepository.save(person);
        assertNotNull(created.getId());
        secondId = created.getId();
    }

    @Test
    @Order(3)
    void testFindById() {
        Person found = personRepository.findById(createdId);
        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(4)
    void testFindByIdNotFound() {
        Person found = personRepository.findById(UUID.randomUUID());
        assertNull(found);
    }

    @Test
    @Order(5)
    void testFindByKeys() {
        Person found = personRepository.findByKeys(createdId);
        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(6)
    void testExistsById() {
        assertTrue(personRepository.existsById(createdId));
        assertFalse(personRepository.existsById(UUID.randomUUID()));
    }

    @Test
    @Order(7)
    void testExistsByEntity() {
        Person person = personRepository.findById(createdId);
        assertTrue(personRepository.exists(person));
    }

    @Test
    @Order(8)
    void testUpdate() {
        Person p = personRepository.findById(createdId);
        p.setName("Updated");

        Person updated = personRepository.update(p);
        assertEquals("Updated", updated.getName());
    }

    @Test
    @Order(9)
    void testMerge() {
        Person person = new Person();
        person.setId(createdId);
        person.setName("Merged");
        person.setAddressId(UUID.randomUUID());

        Person merged = personRepository.merge(person);
        assertEquals("Merged", merged.getName());
    }

    @Test
    @Order(10)
    void testFindAllAndCount() {
        List<Person> all = personRepository.findAll();
        assertFalse(all.isEmpty());
        assertTrue(all.size() >= 2);

        long count = personRepository.count();
        assertEquals(all.size(), count);
    }

    @Test
    @Order(11)
    void testFindAllPaged() {
        Pageable pageable = new Pageable(1, null);
        Paged<Person> page1 = personRepository.findAllPaged(pageable, null);

        assertNotNull(page1);
        assertEquals(1, page1.content().size());

        if (page1.nextPagingState() != null) {
            Pageable pageable2 = new Pageable(1, page1.nextPagingState());
            Paged<Person> page2 = personRepository.findAllPaged(pageable2, null);
            assertNotNull(page2);
            assertFalse(page2.content().isEmpty());

            assertNotEquals(
                    page1.content().get(0).getId(),
                    page2.content().get(0).getId());
        }
    }

    @Test
    @Order(12)
    void testFindAllWithPageable() {
        Pageable pageable = new Pageable(10, null);
        List<Person> results = personRepository.findAll(pageable, null);

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    @Order(13)
    void testQuerySingle() {
        Person found = personRepository.querySingle(
                "SELECT * FROM person WHERE id = ?", createdId);
        assertNotNull(found);
        assertEquals(createdId, found.getId());
    }

    @Test
    @Order(14)
    void testQueryList() {
        List<Person> results = personRepository.query("SELECT * FROM person");
        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    @Order(15)
    void testQuerySingleNotFound() {
        Person found = personRepository.querySingle(
                "SELECT * FROM person WHERE id = ?", UUID.randomUUID());
        assertNull(found);
    }

    @Test
    @Order(16)
    void testQueryWithNamedParams() {
        List<Person> results = personRepository.query(
                "SELECT * FROM person WHERE name = :name", Map.of("name", "Merged"));
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("Merged", results.get(0).getName());
    }

    @Test
    @Order(17)
    void testDeleteById() {
        personRepository.deleteById(secondId);
        assertFalse(personRepository.existsById(secondId));
    }

    @Test
    @Order(18)
    void testDeleteByKeys() {
        Person temp = new Person();
        temp.setId(UUID.randomUUID());
        temp.setName("ToDeleteByKeys");
        Person saved = personRepository.save(temp);

        personRepository.deleteByKeys(saved.getId());
        assertFalse(personRepository.existsById(saved.getId()));
    }

    @Test
    @Order(19)
    void testDelete() {
        Person p = personRepository.findById(createdId);
        personRepository.delete(p);

        assertFalse(personRepository.existsById(createdId));
    }

    @Test
    @Order(20)
    void testSaveWithGeneratedUuid() {
        Person person = new Person();
        person.setName("AutoUUID");

        Person saved = personRepository.save(person);
        assertNotNull(saved.getId(), "UUID should be auto-generated");
        assertEquals("AutoUUID", saved.getName());

        personRepository.deleteById(saved.getId());
    }
}
