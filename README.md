# Quarkus ScyllaDB ORM Extension

A high-performance Quarkus extension for ScyllaDB/Cassandra that provides annotation-based entity mapping, automatic repository generation, and full reactive support via SmallRye Mutiny.

[![Maven Central](https://img.shields.io/maven-central/v/de.prgrm.quarkus-scylladb-orm/quarkus-scylladb-orm)](https://central.sonatype.com/artifact/de.prgrm.quarkus-scylladb-orm/quarkus-scylladb-orm)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **Annotation-based entity mapping** - Define entities with `@Table`, `@PartitionKey`, `@ClusteringKey`, etc.
- **Automatic repository generation** - Generate blocking and/or reactive repositories at build time
- **Full reactive support** - First-class Mutiny integration with `Uni<T>` and `Multi<T>`
- **Custom queries** - Define CQL queries with named parameters via `@Query`
- **Pagination** - Token-based paging optimized for ScyllaDB
- **Prepared statement caching** - Automatic caching for optimal performance
- **Type conversion** - Built-in converters with custom converter support
- **CDI integration** - Inject repositories directly into your beans

## Requirements

- Java 21+
- Quarkus 3.x
- ScyllaDB or Apache Cassandra

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>de.prgrm.quarkus-scylladb-orm</groupId>
    <artifactId>quarkus-scylladb-orm</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### 1. Define Your Entity

```java
import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("person")
@GenerateRepository
public class Person {

    @PartitionKey
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column("full_name")
    private String name;

    @Column
    private int age;

    // Getters and setters
}
```

### 2. Inject and Use the Repository

```java
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PersonService {

    @Inject
    PersonBaseRepository personRepository;

    public Person createPerson(String name, int age) {
        Person person = new Person();
        person.setName(name);
        person.setAge(age);
        return personRepository.save(person);  // UUID auto-generated
    }

    public Person findById(UUID id) {
        return personRepository.findById(id);
    }

    public List<Person> findAll() {
        return personRepository.findAll();
    }
}
```

### 3. Reactive Usage

```java
@ApplicationScoped
public class PersonReactiveService {

    @Inject
    PersonBaseReactiveRepository personRepository;

    public Uni<Person> createPerson(String name) {
        Person person = new Person();
        person.setName(name);
        return personRepository.save(person);
    }

    public Multi<Person> streamAll() {
        return personRepository.findAll();
    }
}
```

## Entity Mapping

### Annotations

| Annotation | Description |
|------------|-------------|
| `@Table("name")` | Maps class to a table |
| `@PartitionKey` | Marks field as partition key (use `ordinal` for composite keys) |
| `@ClusteringKey` | Marks field as clustering key (supports `ordinal` and `order`) |
| `@Column("name")` | Maps field to column (optional, defaults to field name) |
| `@GeneratedValue` | Auto-generates values (UUID or SEQUENCE) |
| `@Transient` | Excludes field from persistence |
| `@Enumerated` | Enum handling (STRING or ORDINAL) |
| `@Convert` | Custom type conversion |

### Composite Primary Keys

```java
@Table("sensor_data")
@GenerateRepository
public class SensorData {

    @PartitionKey(ordinal = 0)
    private String sensorId;

    @PartitionKey(ordinal = 1)
    private String region;

    @ClusteringKey(ordinal = 0, order = ClusteringOrder.DESC)
    private Instant timestamp;

    @Column
    private double value;
}
```

Access with composite keys:

```java
// Find by full primary key (partition + clustering)
SensorData data = repository.findByKeys("sensor-1", "us-east", timestamp);

// Delete by full primary key
repository.deleteByKeys("sensor-1", "us-east", timestamp);
```

## Repository Operations

### Blocking Repository

```java
// CRUD
T save(T entity)
T update(T entity)
T merge(T entity)
void delete(T entity)
void deleteById(ID id)
void deleteByKeys(Object... keys)

// Queries
T findById(ID id)
T findByKeys(Object... keys)
List<T> findAll()
long count()

// Existence
boolean exists(T entity)
boolean existsById(ID id)

// Custom CQL
List<T> query(String cql, Object... params)
T querySingle(String cql, Object... params)
void execute(String cql, Object... params)
```

### Reactive Repository

Same operations returning Mutiny types:
- `Uni<T>` for single results
- `Multi<T>` for collections
- `Uni<Void>` for void operations
- `Uni<Long>` for counts
- `Uni<Boolean>` for existence checks

## Pagination

```java
// First page
Pageable pageable = Pageable.ofSize(20);
Paged<Person> page1 = personRepository.findAllPaged(pageable, null);

// Process results
page1.content().forEach(this::process);

// Next page (if exists)
if (page1.hasNextPage()) {
    Pageable nextPageable = Pageable.of(20, page1.nextPagingState());
    Paged<Person> page2 = personRepository.findAllPaged(nextPageable, null);
}
```

### With Sorting

```java
Sortable sort = Sortable.desc("created_at");
Paged<Person> page = personRepository.findAllPaged(pageable, sort);
```

## Custom Queries

### Using @Query Annotation

```java
@Table("book")
@GenerateRepository
@Queries({
    @Query(
        name = "findByTitle",
        cql = "SELECT * FROM book WHERE title = :title ALLOW FILTERING",
        returnType = Query.ReturnType.SINGLE,
        paramTypes = @Query.Param(name = "title", type = String.class)
    ),
    @Query(
        name = "findAllActive",
        cql = "SELECT * FROM book WHERE active = :active ALLOW FILTERING",
        returnType = Query.ReturnType.LIST,
        paramTypes = @Query.Param(name = "active", type = Boolean.class)
    ),
    @Query(
        name = "deactivateAll",
        cql = "UPDATE book SET active = false WHERE id = :id",
        returnType = Query.ReturnType.VOID,
        paramTypes = @Query.Param(name = "id", type = UUID.class)
    )
})
public class Book {
    @PartitionKey
    private UUID id;
    private String title;
    private boolean active;
}
```

Generated methods:

```java
Book book = bookRepository.findByTitle("Clean Code");
List<Book> activeBooks = bookRepository.findAllActive(true);
bookRepository.deactivateAll(bookId);
```

### Runtime Custom Queries

```java
// Positional parameters
List<Person> results = repository.query(
    "SELECT * FROM person WHERE age > ? ALLOW FILTERING",
    21
);

// Named parameters
List<Person> results = repository.query(
    "SELECT * FROM person WHERE name = :name AND age > :minAge ALLOW FILTERING",
    Map.of("name", "John", "minAge", 21)
);
```

## Configuration

Configure your ScyllaDB connection in `application.properties`:

```properties
# Contact points
quarkus.cassandra.contact-points=localhost:9042

# Authentication (if needed)
quarkus.cassandra.auth.username=cassandra
quarkus.cassandra.auth.password=cassandra

# Keyspace
quarkus.cassandra.keyspace=my_keyspace

# Local datacenter
quarkus.cassandra.local-datacenter=datacenter1
```

## Type Converters

### Built-in Converters

The extension handles common Java types automatically:
- `UUID`, `String`, `Integer`, `Long`, `Double`, `Float`, `Boolean`
- `Instant`, `LocalDate`, `LocalDateTime`, `LocalTime`
- `BigDecimal`, `BigInteger`
- `byte[]`, `ByteBuffer`
- Collections: `List`, `Set`, `Map`

### Custom Converters

```java
public class JsonConverter implements AttributeConverter<MyObject, String> {

    @Override
    public String convertToDatabaseColumn(MyObject attribute) {
        return objectMapper.writeValueAsString(attribute);
    }

    @Override
    public MyObject convertToEntityAttribute(String dbData) {
        return objectMapper.readValue(dbData, MyObject.class);
    }
}

// Usage
@Table("my_table")
public class MyEntity {
    @Convert(JsonConverter.class)
    private MyObject data;
}
```

## Repository Generation Modes

Control which repositories are generated:

```java
// Generate both (default)
@GenerateRepository
@GenerateRepository(RepositoryType.BOTH)

// Blocking only
@GenerateRepository(RepositoryType.BLOCKING)

// Reactive only
@GenerateRepository(RepositoryType.REACTIVE)
```

## Best Practices

### Use Prepared Statements

The ORM automatically caches prepared statements. All repository methods use prepared statements for optimal performance.

### Pagination for Large Datasets

Always use pagination when querying large datasets:

```java
// Good
Paged<Data> page = repository.findAllPaged(Pageable.ofSize(100), null);

// Avoid for large tables
List<Data> all = repository.findAll();
```

### Composite Keys

Design your data model with ScyllaDB's partition and clustering key model in mind:

```java
@Table("time_series")
public class TimeSeries {
    @PartitionKey
    private String metricName;  // Partition key for distribution

    @ClusteringKey(order = ClusteringOrder.DESC)
    private Instant timestamp;  // Clustering for time-ordered access

    private double value;
}
```

### Reactive for High Throughput

Use reactive repositories for high-throughput scenarios:

```java
public Multi<ProcessedData> processStream() {
    return repository.findAll()
        .onItem().transform(this::process)
        .onFailure().retry().atMost(3);
}
```

## Building from Source

```bash
git clone https://github.com/UnvirtualHH/quarkus-scylladb-orm.git
cd quarkus-scylladb-orm
mvn clean install
```

Run tests (requires Docker for Testcontainers):

```bash
mvn verify
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
