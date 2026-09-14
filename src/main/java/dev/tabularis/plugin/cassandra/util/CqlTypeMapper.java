package dev.tabularis.plugin.cassandra.util;

import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;

/**
 * Maps CQL {@link DataType}s to the {@code data_type}/{@code category}
 * strings the manifest's {@code data_types} array and {@code get_columns}
 * responses use (plugins/PLUGIN_GUIDE.md). We render the type exactly as CQL
 * would print it - including collection/frozen/UDT generics via
 * {@link DataType#asCql(boolean, boolean)} - so what the grid shows always
 * matches what {@code DESCRIBE TABLE} would.
 */
public final class CqlTypeMapper {

    private CqlTypeMapper() {
    }

    /** The exact CQL spelling of a type, e.g. {@code list<frozen<map<text, int>>>}. */
    public static String cqlName(DataType type) {
        // includeFrozen=true so implicitly-frozen nested collections/UDTs
        // render the same way `DESCRIBE TABLE` would show them.
        return type.asCql(true, true);
    }

    /**
     * A coarse UI category for the given CQL type: one of {@code numeric},
     * {@code string}, {@code date}, {@code binary}, {@code json}, {@code
     * boolean}, {@code uuid}, {@code collection}, or {@code other}. Matches
     * the {@code category} values documented for manifest {@code data_types}
     * entries, extended with the categories the Tabularis UI already
     * recognizes for non-SQL drivers (boolean/uuid/collection).
     */
    public static String category(DataType type) {
        if (isNumeric(type)) {
            return "numeric";
        }
        if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII) || type.equals(DataTypes.INET)) {
            return "string";
        }
        if (type.equals(DataTypes.TIMESTAMP) || type.equals(DataTypes.DATE) || type.equals(DataTypes.TIME) || type.equals(DataTypes.DURATION)) {
            return "date";
        }
        if (type.equals(DataTypes.BLOB)) {
            return "binary";
        }
        if (type.equals(DataTypes.BOOLEAN)) {
            return "boolean";
        }
        if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
            return "uuid";
        }
        String cql = cqlName(type);
        if (cql.startsWith("list<") || cql.startsWith("set<") || cql.startsWith("map<") || cql.startsWith("tuple<") || cql.startsWith("frozen<")) {
            return "collection";
        }
        return "other";
    }

    private static boolean isNumeric(DataType type) {
        return type.equals(DataTypes.TINYINT) || type.equals(DataTypes.SMALLINT) || type.equals(DataTypes.INT)
                || type.equals(DataTypes.BIGINT) || type.equals(DataTypes.VARINT) || type.equals(DataTypes.FLOAT)
                || type.equals(DataTypes.DOUBLE) || type.equals(DataTypes.DECIMAL) || type.equals(DataTypes.COUNTER);
    }
}
