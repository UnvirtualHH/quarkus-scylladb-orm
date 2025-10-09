package io.quarkiverse.quarkus.scylladb.orm.it.person;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Person;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonRepositoryTest {

    @Inject
    PersonBaseRepository personRepository;

    private static UUID createdId;

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
    void testFindById() {
        Person found = personRepository.findById(createdId);
        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(3)
    void testUpdate() {
        Person p = personRepository.findById(createdId);
        p.setName("Updated");

        Person updated = personRepository.update(p);
        assertEquals("Updated", updated.getName());
    }

    @Test
    @Order(4)
    void testFindAllAndCount() {
        List<Person> all = personRepository.findAll();
        assertFalse(all.isEmpty());

        long count = personRepository.count();
        assertEquals(all.size(), count);
    }

    @Test
    @Order(5)
    void testDelete() {
        Person p = personRepository.findById(createdId);
        personRepository.delete(p);

        assertFalse(personRepository.existsById(createdId));
    }
}
