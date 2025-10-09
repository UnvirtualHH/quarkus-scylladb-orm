package io.quarkiverse.quarkus.scylladb.orm.it.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Person;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonMapper;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MapperTest {

    @Inject
    PersonMapper personMapper;

    @Test
    void testMapperExists() {
        assertNotNull(personMapper);
    }

    @Test
    void testMapperToDb() {
        Person person = new Person();
        person.setId(UUID.randomUUID());
        person.setName("John Doe");
        person.setAddressId(UUID.randomUUID());
        Map<String, Object> dbMap = personMapper.toProperties(person);

        assertNotNull(dbMap);
        assertEquals(3, dbMap.size());

        assertEquals("John Doe", dbMap.get("name"));
        assertEquals(person.getId(), dbMap.get("id"));
    }
}
