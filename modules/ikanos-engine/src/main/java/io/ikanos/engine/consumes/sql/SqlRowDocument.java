/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.engine.consumes.sql;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Builds the JSON document a SQL call returns, {@code {"rows": [...], "rowCount": n}}, at the
 * source, so {@code ConsumedResult.document()} hands it over without a serialize/parse round trip.
 *
 * <p>Each row is an object keyed by {@code ResultSetMetaData.getColumnLabel()}, as the driver
 * returns it. Exact numerics keep their {@link BigDecimal} precision; dates and times become
 * ISO-8601 strings; JSON columns become JSON values; binary columns are refused, naming the
 * column. There is no row cap: large results wait for engine-level pagination.</p>
 */
final class SqlRowDocument {

    static final String ROWS = "rows";
    static final String ROW_COUNT = "rowCount";

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final JsonNodeFactory NODES = JSON.getNodeFactory();

    private SqlRowDocument() {
    }

    /** @return a document with no rows */
    static ObjectNode empty() {
        ObjectNode document = NODES.objectNode();
        document.set(ROWS, NODES.arrayNode());
        document.put(ROW_COUNT, 0);
        return document;
    }

    /**
     * Read every row of a result set into a new document.
     *
     * @throws SQLException when the driver fails
     * @throws SqlClientException when a column has a type the document cannot carry
     */
    static ObjectNode read(ResultSet resultSet) throws SQLException {
        ObjectNode document = NODES.objectNode();
        ArrayNode rows = document.putArray(ROWS);
        ResultSetMetaData meta = resultSet.getMetaData();
        int columns = meta.getColumnCount();
        String[] labels = new String[columns];
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int c = 1; c <= columns; c++) {
            labels[c - 1] = meta.getColumnLabel(c);
            if (!seen.add(labels[c - 1])) {
                throw new SqlClientException("column '" + labels[c - 1]
                        + "' appears twice in the result; give each column its own alias");
            }
        }
        while (resultSet.next()) {
            ObjectNode row = rows.addObject();
            for (int c = 1; c <= columns; c++) {
                row.set(labels[c - 1], column(resultSet, meta, c, labels[c - 1]));
            }
        }
        document.put(ROW_COUNT, rows.size());
        return document;
    }

    /** Convert one column value. Package-private for tests. */
    static JsonNode column(ResultSet rs, ResultSetMetaData meta, int c, String label)
            throws SQLException {
        int type = meta.getColumnType(c);
        switch (type) {
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = rs.getBoolean(c);
                return rs.wasNull() ? NODES.nullNode() : NODES.booleanNode(value);
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                long value = rs.getLong(c);
                return rs.wasNull() ? NODES.nullNode() : NODES.numberNode(value);
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal value = rs.getBigDecimal(c);
                return value == null ? NODES.nullNode() : NODES.numberNode(value);
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                double value = rs.getDouble(c);
                return rs.wasNull() ? NODES.nullNode() : NODES.numberNode(value);
            }
            case Types.DATE -> {
                return temporal(rs, c, LocalDate.class);
            }
            case Types.TIME -> {
                return temporal(rs, c, LocalTime.class);
            }
            case Types.TIME_WITH_TIMEZONE -> {
                return temporal(rs, c, OffsetTime.class);
            }
            case Types.TIMESTAMP -> {
                return temporal(rs, c, LocalDateTime.class);
            }
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                return temporal(rs, c, OffsetDateTime.class);
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> throw
                    new SqlClientException("column '" + label
                            + "' is binary, which SQL procedures do not return yet");
            case Types.ARRAY -> {
                Array array = rs.getArray(c);
                if (array == null) {
                    return NODES.nullNode();
                }
                try {
                    return JSON.valueToTree(array.getArray());
                } finally {
                    array.free();
                }
            }
            case Types.NULL -> {
                return NODES.nullNode();
            }
            default -> {
                String value = rs.getString(c);
                if (value == null) {
                    return NODES.nullNode();
                }
                return isJsonColumn(meta.getColumnTypeName(c)) ? parseJson(value, label)
                        : NODES.stringNode(value);
            }
        }
    }

    /**
     * Read a date or time as its ISO-8601 text. Drivers that cannot return the {@code java.time}
     * type (SQLite stores dates as text) fall back to the driver's own text.
     */
    private static JsonNode temporal(ResultSet rs, int c, Class<?> type) throws SQLException {
        Object value;
        try {
            value = rs.getObject(c, type);
        } catch (SQLException | RuntimeException unsupported) {
            value = rs.getString(c);
        }
        return value == null ? NODES.nullNode() : NODES.stringNode(value.toString());
    }

    private static boolean isJsonColumn(String typeName) {
        if (typeName == null) {
            return false;
        }
        String lower = typeName.toLowerCase(Locale.ROOT);
        return lower.equals("json") || lower.equals("jsonb");
    }

    private static JsonNode parseJson(String value, String label) {
        try {
            return JSON.readTree(value);
        } catch (JacksonException e) {
            throw new SqlClientException("column '" + label + "' is not valid JSON");
        }
    }
}
