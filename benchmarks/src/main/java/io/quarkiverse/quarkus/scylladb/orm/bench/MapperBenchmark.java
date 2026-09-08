package io.quarkiverse.quarkus.scylladb.orm.bench;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.cql.Row;

import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchEvent;
import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchEventMapper;
import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchSettings;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What the annotation processor generates, on the hot path.
 * <p>
 * {@code map} runs once per row of every read and {@code toProperties} once per write,
 * so these two methods carry essentially all of the per-row cost this project is
 * responsible for. Everything around them here is fake, so a regression in the numbers
 * points at the generated code and nothing else.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MapperBenchmark {

    private BenchEventMapper mapper;
    private Row row;
    private BenchEvent entity;

    @Setup
    public void setUp() {
        // The mapper only touches its registry field in registerSelf(), which is a CDI
        // callback — constructing it directly is enough for the two methods measured.
        mapper = new BenchEventMapper();

        row = new FakeRow(Map.ofEntries(
                Map.entry("tenant", "acme"),
                Map.entry("device_id", UUID.fromString("11111111-2222-3333-4444-555555555555")),
                Map.entry("occurred_at", Instant.parse("2026-09-08T10:15:30Z")),
                Map.entry("name", "temperature-reading"),
                Map.entry("amount", new BigDecimal("1234.56")),
                Map.entry("status", "DONE"),
                Map.entry("tier", 1),
                Map.entry("tags", List.of("alpha", "beta", "gamma")),
                Map.entry("attributes", Map.of("locale", "de", "region", "eu")),
                Map.entry("settings", "dark:14")));

        entity = mapper.map(row);
        entity.setSettings(new BenchSettings("dark", 14));
    }

    /** One row of a read: what {@code findAll}, {@code query} and every @Query pay per row. */
    @Benchmark
    public BenchEvent mapRowToEntity() {
        return mapper.map(row);
    }

    /** One write: what {@code save}, {@code merge} and {@code update} pay per statement. */
    @Benchmark
    public Map<String, Object> entityToProperties() {
        return mapper.toProperties(entity);
    }

    /** The constant per-entity metadata the repositories ask for on every call. */
    @Benchmark
    public void keyMetadata(Blackhole bh) {
        bh.consume(mapper.getColumnNames());
        bh.consume(mapper.getPartitionKeyNames());
        bh.consume(mapper.getClusteringKeyNames());
    }

    /** Building the bind parameters for a keyed read or delete. */
    @Benchmark
    public void keyComponents(Blackhole bh) {
        bh.consume(mapper.getPartitionKeyComponents(entity));
        bh.consume(mapper.getClusteringKeyComponents(entity));
    }
}
