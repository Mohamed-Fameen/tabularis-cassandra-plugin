package dev.tabularis.plugin.cassandra.util;

import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CqlTypeMapperTest {

    @Test
    void simpleTypesRenderAsLowercaseCql() {
        assertThat(CqlTypeMapper.cqlName(DataTypes.TEXT)).isEqualTo("text");
        assertThat(CqlTypeMapper.cqlName(DataTypes.INT)).isEqualTo("int");
        assertThat(CqlTypeMapper.cqlName(DataTypes.TIMEUUID)).isEqualTo("timeuuid");
    }

    @Test
    void collectionTypesRenderWithGenerics() {
        DataType listOfText = DataTypes.listOf(DataTypes.TEXT);
        assertThat(CqlTypeMapper.cqlName(listOfText)).isEqualTo("list<text>");

        DataType mapOfTextToInt = DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT);
        assertThat(CqlTypeMapper.cqlName(mapOfTextToInt)).isEqualTo("map<text, int>");
    }

    @Test
    void numericTypesAreCategorizedAsNumeric() {
        assertThat(CqlTypeMapper.category(DataTypes.INT)).isEqualTo("numeric");
        assertThat(CqlTypeMapper.category(DataTypes.BIGINT)).isEqualTo("numeric");
        assertThat(CqlTypeMapper.category(DataTypes.DOUBLE)).isEqualTo("numeric");
        assertThat(CqlTypeMapper.category(DataTypes.DECIMAL)).isEqualTo("numeric");
        assertThat(CqlTypeMapper.category(DataTypes.COUNTER)).isEqualTo("numeric");
    }

    @Test
    void textAndInetAreCategorizedAsString() {
        assertThat(CqlTypeMapper.category(DataTypes.TEXT)).isEqualTo("string");
        assertThat(CqlTypeMapper.category(DataTypes.ASCII)).isEqualTo("string");
        assertThat(CqlTypeMapper.category(DataTypes.INET)).isEqualTo("string");
    }

    @Test
    void temporalTypesAreCategorizedAsDate() {
        assertThat(CqlTypeMapper.category(DataTypes.TIMESTAMP)).isEqualTo("date");
        assertThat(CqlTypeMapper.category(DataTypes.DATE)).isEqualTo("date");
        assertThat(CqlTypeMapper.category(DataTypes.TIME)).isEqualTo("date");
    }

    @Test
    void collectionsAreCategorizedAsCollection() {
        assertThat(CqlTypeMapper.category(DataTypes.listOf(DataTypes.TEXT))).isEqualTo("collection");
        assertThat(CqlTypeMapper.category(DataTypes.setOf(DataTypes.UUID))).isEqualTo("collection");
        assertThat(CqlTypeMapper.category(DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT))).isEqualTo("collection");
    }

    @Test
    void blobIsBinaryAndBooleanAndUuidHaveDedicatedCategories() {
        assertThat(CqlTypeMapper.category(DataTypes.BLOB)).isEqualTo("binary");
        assertThat(CqlTypeMapper.category(DataTypes.BOOLEAN)).isEqualTo("boolean");
        assertThat(CqlTypeMapper.category(DataTypes.UUID)).isEqualTo("uuid");
        assertThat(CqlTypeMapper.category(DataTypes.TIMEUUID)).isEqualTo("uuid");
    }
}
