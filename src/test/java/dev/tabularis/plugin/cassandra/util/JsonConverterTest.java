package dev.tabularis.plugin.cassandra.util;

import com.datastax.oss.driver.api.core.type.DataTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullValueBecomesJsonNull() {
        assertThat(JsonConverter.toJson(mapper, null).isNull()).isTrue();
    }

    @Test
    void uuidRendersAsString() {
        UUID id = UUID.randomUUID();
        assertThat(JsonConverter.toJson(mapper, id).asText()).isEqualTo(id.toString());
    }

    @Test
    void bigDecimalAndBigIntegerRenderAsStringsToAvoidPrecisionLoss() {
        assertThat(JsonConverter.toJson(mapper, new BigInteger("123456789012345678901234567890")).isTextual()).isTrue();
        assertThat(JsonConverter.toJson(mapper, new BigDecimal("1.000000000000000001")).isTextual()).isTrue();
    }

    @Test
    void blobRendersAsBase64String() {
        byte[] bytes = {1, 2, 3, 4};
        JsonNode node = JsonConverter.toJson(mapper, ByteBuffer.wrap(bytes));
        assertThat(node.asText()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    void listRendersRecursivelyAsJsonArray() {
        JsonNode node = JsonConverter.toJson(mapper, List.of("a", "b"));
        assertThat(node.isArray()).isTrue();
        assertThat(node.get(0).asText()).isEqualTo("a");
        assertThat(node.get(1).asText()).isEqualTo("b");
    }

    @Test
    void toCqlValueParsesUuidTimestampAndBoolean() {
        UUID id = UUID.randomUUID();
        assertThat(JsonConverter.toCqlValue(mapper.valueToTree(id.toString()), DataTypes.UUID)).isEqualTo(id);

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(JsonConverter.toCqlValue(mapper.valueToTree(now.toString()), DataTypes.TIMESTAMP)).isEqualTo(now);

        assertThat(JsonConverter.toCqlValue(mapper.valueToTree(true), DataTypes.BOOLEAN)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void toCqlValueParsesBlobFromBase64() {
        byte[] bytes = {5, 6, 7};
        String encoded = Base64.getEncoder().encodeToString(bytes);
        Object value = JsonConverter.toCqlValue(mapper.valueToTree(encoded), DataTypes.BLOB);
        assertThat(value).isInstanceOf(ByteBuffer.class);
        ByteBuffer buf = (ByteBuffer) value;
        byte[] roundTripped = new byte[buf.remaining()];
        buf.get(roundTripped);
        assertThat(roundTripped).isEqualTo(bytes);
    }

    @Test
    void toCqlValueRejectsUnsupportedCollectionTypesWithAClearMessage() {
        assertThatThrownBy(() -> JsonConverter.toCqlValue(mapper.valueToTree("x"), DataTypes.listOf(DataTypes.TEXT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not yet supported");
    }

    @Test
    void toCqlValueReturnsNullForJsonNull() {
        assertThat(JsonConverter.toCqlValue(mapper.nullNode(), DataTypes.TEXT)).isNull();
    }
}
