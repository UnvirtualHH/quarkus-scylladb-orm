package io.quarkiverse.quarkus.scylladb.orm.it.processor;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.quarkus.scylladb.orm.it.model.EventBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class RepositoryTest {

    @Inject
    PersonBaseRepository personBaseRepository;

    @Inject
    PersonBaseReactiveRepository personBaseReactiveRepository;

    @Test
    public void testGeneratedRepositoryIsPresent() {
        Assertions.assertNotNull(personBaseRepository);
    }

    @Test
    public void testGeneratedReactiveRepositoryIsPresent() {
        Assertions.assertNotNull(personBaseReactiveRepository);
    }

    /**
     * The ID type parameter is the partition key's own type, so findById/deleteById/
     * existsById are type-safe. It used to be Object for every entity.
     */
    @Test
    public void generatedRepositoriesAreTypedOnTheirPartitionKey() {
        Assertions.assertEquals(UUID.class, idTypeOf(PersonBaseRepository.class));
        Assertions.assertEquals(UUID.class, idTypeOf(PersonBaseReactiveRepository.class));
    }

    /**
     * With a composite partition key findById cannot work, so the ID parameter stays
     * Object and callers are pushed towards findByKeys.
     */
    @Test
    public void compositePartitionKeysFallBackToObject() {
        Assertions.assertEquals(Object.class, idTypeOf(EventBaseRepository.class));
    }

    private static Type idTypeOf(Class<?> repositoryClass) {
        ParameterizedType superclass = (ParameterizedType) repositoryClass.getGenericSuperclass();
        return superclass.getActualTypeArguments()[1];
    }
}
