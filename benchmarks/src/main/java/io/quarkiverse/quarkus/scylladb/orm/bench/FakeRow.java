package io.quarkiverse.quarkus.scylladb.orm.bench;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.detach.AttachmentPoint;
import com.datastax.oss.driver.api.core.type.DataType;

/**
 * A {@link Row} that hands back canned values by column name.
 * <p>
 * A real row would spend most of its time in the driver's codecs, decoding bytes. That
 * cost belongs to the driver, not to this project, and it would swamp what these
 * benchmarks are actually about: the code the annotation processor generates around it.
 * <p>
 * Deliberately a class, not a {@link java.lang.reflect.Proxy}: a proxy's reflective
 * dispatch and argument boxing on every accessor would be a large share of the measured
 * time. The generated mappers only ever call the three accessors overridden below —
 * everything else throws, so that stays true.
 */
public final class FakeRow implements Row {

    private final Map<String, Object> values;

    public FakeRow(Map<String, Object> values) {
        this.values = values;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <ValueT> ValueT get(String name, Class<ValueT> targetClass) {
        return (ValueT) values.get(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <ElementT> List<ElementT> getList(String name, Class<ElementT> elementsClass) {
        return (List<ElementT>) values.get(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <ElementT> Set<ElementT> getSet(String name, Class<ElementT> elementsClass) {
        return (Set<ElementT>) values.get(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <KeyT, ValueT> Map<KeyT, ValueT> getMap(String name, Class<KeyT> keyClass, Class<ValueT> valueClass) {
        return (Map<KeyT, ValueT>) values.get(name);
    }

    // --- Everything below is untouched by the generated mappers. ---

    @Override
    public ColumnDefinitions getColumnDefinitions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int firstIndexOf(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DataType getType(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int firstIndexOf(com.datastax.oss.driver.api.core.CqlIdentifier id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DataType getType(com.datastax.oss.driver.api.core.CqlIdentifier id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuffer getBytesUnsafe(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DataType getType(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry codecRegistry() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.datastax.oss.driver.api.core.ProtocolVersion protocolVersion() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDetached() {
        return false;
    }

    @Override
    public void attach(AttachmentPoint attachmentPoint) {
        // nothing to attach
    }

    @Override
    public String toString() {
        return "FakeRow" + values.keySet();
    }
}
