package io.quarkiverse.quarkus.scylladb.orm.bench;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.cql.Row;

import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchEvent;
import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchEventMapper;
import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchSettings;
import io.quarkiverse.quarkus.scylladb.orm.bench.model.BenchSettingsConverter;
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
 * What the annotation processor generates, on the hot path — and what to judge it by.
 * <p>
 * {@code map} runs once per row of every read and {@code toProperties} once per write,
 * so those two carry essentially all of the per-row cost this project is responsible
 * for. A bare nanosecond figure for them cannot be called good or bad, so two baselines
 * sit next to them in the same state:
 * <ul>
 * <li>{@link #rowAccessOnly} does nothing but read the same columns off the same
 * {@link FakeRow}. Whatever it costs is the harness, not the generated code — subtract
 * it before drawing any conclusion about the mapper.</li>
 * <li>{@link #handWrittenMap} does the identical mapping by hand, the way you would if
 * this library did not exist. The gap to the generated mapper is what the code
 * generation actually costs you.</li>
 * </ul>
 * <p>
 * Everything around the mapper is fake, so a change in these numbers points at the
 * generated code and nothing else.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MapperBenchmark {

    private static final BenchSettingsConverter CONVERTER = new BenchSettingsConverter();
    private static final BenchEvent.Tier[] TIERS = BenchEvent.Tier.values();

    private BenchEventMapper mapper;
    private Row row;
    private BenchEvent entity;

    @Setup
    public void setUp() {
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

    /** The floor: the column reads alone, with no mapping at all. */
    @Benchmark
    public void rowAccessOnly(Blackhole bh) {
        bh.consume(row.get("tenant", String.class));
        bh.consume(row.get("device_id", UUID.class));
        bh.consume(row.get("occurred_at", Instant.class));
        bh.consume(row.get("name", String.class));
        bh.consume(row.get("amount", BigDecimal.class));
        bh.consume(row.get("status", String.class));
        bh.consume(row.get("tier", Integer.class));
        bh.consume(row.getList("tags", String.class));
        bh.consume(row.getMap("attributes", String.class, String.class));
        bh.consume(row.get("settings", String.class));
    }

    /** The same mapping, written out by hand. */
    @Benchmark
    public BenchEvent handWrittenMap() {
        BenchEvent instance = new BenchEvent();
        instance.setTenant(row.get("tenant", String.class));
        instance.setDeviceId(row.get("device_id", UUID.class));
        instance.setOccurredAt(row.get("occurred_at", Instant.class));
        instance.setName(row.get("name", String.class));
        instance.setAmount(row.get("amount", BigDecimal.class));

        String status = row.get("status", String.class);
        if (status != null) {
            instance.setStatus(BenchEvent.Status.valueOf(status));
        }
        Integer tier = row.get("tier", Integer.class);
        if (tier != null) {
            instance.setTier(TIERS[tier]);
        }
        instance.setTags(row.getList("tags", String.class));
        instance.setAttributes(row.getMap("attributes", String.class, String.class));

        String settings = row.get("settings", String.class);
        if (settings != null) {
            instance.setSettings(CONVERTER.toEntityAttribute(settings));
        }
        return instance;
    }

    /** The write side, written out by hand. */
    @Benchmark
    public Map<String, Object> handWrittenToProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tenant", entity.getTenant());
        props.put("device_id", entity.getDeviceId());
        props.put("occurred_at", entity.getOccurredAt());
        props.put("name", entity.getName());
        props.put("amount", entity.getAmount());
        if (entity.getStatus() != null) {
            props.put("status", entity.getStatus().name());
        }
        if (entity.getTier() != null) {
            props.put("tier", entity.getTier().ordinal());
        }
        props.put("tags", entity.getTags());
        props.put("attributes", entity.getAttributes());
        if (entity.getSettings() != null) {
            props.put("settings", CONVERTER.toCqlColumn(entity.getSettings()));
        }
        return props;
    }

    /** One row of a read: what findAll, query and every @Query pay per row. */
    @Benchmark
    public BenchEvent generatedMap() {
        return mapper.map(row);
    }

    /** One write: what save, merge and update pay per statement. */
    @Benchmark
    public Map<String, Object> generatedToProperties() {
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
