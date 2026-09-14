package dev.tabularis.plugin.cassandra.util;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Converts between CQL row values (as returned by the driver's default codec
 * registry) and JSON, for the {@code execute_query} row payload and the
 * {@code insert_record}/{@code update_record} value parameters.
 *
 * <p>CQL has several value kinds JSON has no native representation for
 * (UUID, blob, timestamp/date/time, inet, duration, decimal/varint). We
 * render those as strings so results survive the JSON-RPC round trip and
 * display sensibly in a grid; {@link #toCqlValue} parses them back for
 * writes. Collections (list/set/map) and tuples/UDTs are converted
 * recursively / via {@code toString()} respectively - see README "Known
 * limitations" for the tuple/UDT write-path caveat.
 */
public final class JsonConverter {

    private JsonConverter() {
    }

    public static JsonNode toJson(ObjectMapper mapper, Object value) {
        if (value == null) {
            return mapper.nullNode();
        }
        if (value instanceof String s) {
            return mapper.getNodeFactory().textNode(s);
        }
        if (value instanceof Boolean b) {
            return mapper.getNodeFactory().booleanNode(b);
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return mapper.getNodeFactory().numberNode(((Number) value).intValue());
        }
        if (value instanceof Long l) {
            return mapper.getNodeFactory().numberNode(l);
        }
        if (value instanceof Float f) {
            return mapper.getNodeFactory().numberNode(f);
        }
        if (value instanceof Double d) {
            return mapper.getNodeFactory().numberNode(d);
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            // Rendered as strings to avoid precision loss over JSON, and
            // because CQL varint/decimal can exceed IEEE-754 double range.
            return mapper.getNodeFactory().textNode(value.toString());
        }
        if (value instanceof UUID uuid) {
            return mapper.getNodeFactory().textNode(uuid.toString());
        }
        if (value instanceof InetAddress addr) {
            return mapper.getNodeFactory().textNode(addr.getHostAddress());
        }
        if (value instanceof Instant instant) {
            return mapper.getNodeFactory().textNode(instant.toString());
        }
        if (value instanceof LocalDate date) {
            return mapper.getNodeFactory().textNode(date.toString());
        }
        if (value instanceof LocalTime time) {
            return mapper.getNodeFactory().textNode(time.toString());
        }
        if (value instanceof CqlDuration duration) {
            return mapper.getNodeFactory().textNode(duration.toString());
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer dup = buffer.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            return mapper.getNodeFactory().textNode(Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof List<?> list) {
            ArrayNode array = mapper.createArrayNode();
            for (Object element : list) {
                array.add(toJson(mapper, element));
            }
            return array;
        }
        if (value instanceof Set<?> set) {
            ArrayNode array = mapper.createArrayNode();
            for (Object element : set) {
                array.add(toJson(mapper, element));
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            var obj = mapper.createObjectNode();
            for (var entry : map.entrySet()) {
                obj.set(String.valueOf(entry.getKey()), toJson(mapper, entry.getValue()));
            }
            return obj;
        }
        // Tuples, UDTs, and anything else without a dedicated JSON shape:
        // best-effort string form (read-only fidelity - see README).
        return mapper.getNodeFactory().textNode(value.toString());
    }

    /** Parses a JSON value into the Java type the given CQL {@link DataType} expects. */
    public static Object toCqlValue(JsonNode node, DataType type) {
        if (node == null || node.isNull()) {
            return null;
        }
        // CQL "varchar" is an alias for "text" - both decode to DataTypes.TEXT,
        // there is no separate DataTypes.VARCHAR constant.
        if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII)) {
            return node.asText();
        }
        if (type.equals(DataTypes.BOOLEAN)) {
            return node.asBoolean();
        }
        if (type.equals(DataTypes.TINYINT)) {
            return (byte) node.asInt();
        }
        if (type.equals(DataTypes.SMALLINT)) {
            return (short) node.asInt();
        }
        if (type.equals(DataTypes.INT)) {
            return node.asInt();
        }
        if (type.equals(DataTypes.BIGINT) || type.equals(DataTypes.COUNTER)) {
            return node.isTextual() ? Long.parseLong(node.asText()) : node.asLong();
        }
        if (type.equals(DataTypes.VARINT)) {
            return new BigInteger(node.asText());
        }
        if (type.equals(DataTypes.FLOAT)) {
            return (float) node.asDouble();
        }
        if (type.equals(DataTypes.DOUBLE)) {
            return node.asDouble();
        }
        if (type.equals(DataTypes.DECIMAL)) {
            return new BigDecimal(node.asText());
        }
        if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
            return UUID.fromString(node.asText());
        }
        if (type.equals(DataTypes.TIMESTAMP)) {
            String text = node.asText();
            try {
                return Instant.parse(text);
            } catch (Exception e) {
                return Instant.ofEpochMilli(Long.parseLong(text));
            }
        }
        if (type.equals(DataTypes.DATE)) {
            return LocalDate.parse(node.asText());
        }
        if (type.equals(DataTypes.TIME)) {
            return LocalTime.parse(node.asText());
        }
        if (type.equals(DataTypes.BLOB)) {
            return ByteBuffer.wrap(Base64.getDecoder().decode(node.asText()));
        }
        if (type.equals(DataTypes.INET)) {
            try {
                return InetAddress.getByName(node.asText());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid inet value: " + node.asText(), e);
            }
        }
        // Collections, tuples, and UDTs are not supported on the write path
        // in v1 - see README "Known limitations". Callers should catch the
        // resulting error and surface it to the user rather than silently
        // truncating data.
        throw new IllegalArgumentException(
                "Writing values of type \"" + CqlTypeMapper.cqlName(type) + "\" is not yet supported; edit via CQL instead");
    }
}
