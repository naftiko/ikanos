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
import java.sql.CallableStatement;
import java.sql.JDBCType;
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
    static final String OUT = "out";

    /** Reads JSON columns; decimals inside them keep their precision, as NUMERIC columns do. */
    private static final ObjectMapper JSON = IkanosMappers.jsonBuilder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
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
                if (type == Types.BIGINT && !meta.isSigned(c)) {
                    // MySQL BIGINT UNSIGNED can exceed Long.MAX_VALUE
                    BigDecimal value = rs.getBigDecimal(c);
                    return value == null ? NODES.nullNode()
                            : NODES.numberNode(value.toBigIntegerExact());
                }
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

    /** @return an integer {@code OUT} value, whichever {@link Number} the driver gave it as */
    private static JsonNode outInteger(Object value, String label) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            return NODES.numberNode(((Number) value).longValue());
        }
        if (value instanceof Boolean flag) {
            // MySQL reads TINYINT(1) as a boolean
            return NODES.numberNode(flag ? 1L : 0L);
        }
        try {
            return NODES.numberNode(new BigDecimal(value.toString().trim()).toBigIntegerExact());
        } catch (ArithmeticException | NumberFormatException e) {
            throw new SqlClientException("OUT parameter '" + label + "' is not an integer");
        }
    }

    /**
     * Read a stored-procedure {@code OUT} value by the type it was registered as, the way
     * {@link #column} reads a result-set column: exact numerics keep their precision, dates and
     * times become ISO-8601 strings, and a cursor becomes the array of its rows, closed once read.
     *
     * @param call the executed call
     * @param position the parameter position
     * @param type the declared {@code sqlType}
     * @param label the parameter name, for error messages
     * @throws SQLException when the driver fails
     * @throws SqlClientException when the value is binary, or an integer type holds a fraction
     */
    static JsonNode out(CallableStatement call, int position, JDBCType type, String label)
            throws SQLException {
        switch (type) {
            case BOOLEAN, BIT -> {
                boolean value = call.getBoolean(position);
                return call.wasNull() ? NODES.nullNode() : NODES.booleanNode(value);
            }
            case TINYINT, SMALLINT, INTEGER, BIGINT -> {
                // getObject: PostgreSQL refuses a typed getter that differs from the registered
                // type, and MySQL gives BIGINT UNSIGNED as a BigInteger past Long.MAX_VALUE
                return outInteger(call.getObject(position), label);
            }
            case NUMERIC, DECIMAL -> {
                BigDecimal value = call.getBigDecimal(position);
                return value == null ? NODES.nullNode() : NODES.numberNode(value);
            }
            case REAL -> {
                float value = call.getFloat(position);
                return call.wasNull() ? NODES.nullNode() : NODES.numberNode(value);
            }
            case FLOAT, DOUBLE -> {
                double value = call.getDouble(position);
                return call.wasNull() ? NODES.nullNode() : NODES.numberNode(value);
            }
            case DATE -> {
                return outTemporal(call, position, LocalDate.class);
            }
            case TIME -> {
                return outTemporal(call, position, LocalTime.class);
            }
            case TIME_WITH_TIMEZONE -> {
                return outTemporal(call, position, OffsetTime.class);
            }
            case TIMESTAMP -> {
                return outTemporal(call, position, LocalDateTime.class);
            }
            case TIMESTAMP_WITH_TIMEZONE -> {
                return outTemporal(call, position, OffsetDateTime.class);
            }
            case CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR -> {
                String value = call.getString(position);
                return value == null ? NODES.nullNode() : NODES.stringNode(value);
            }
            case ARRAY -> {
                Array array = call.getArray(position);
                if (array == null) {
                    return NODES.nullNode();
                }
                try {
                    return JSON.valueToTree(array.getArray());
                } finally {
                    array.free();
                }
            }
            default -> {
                return value(call.getObject(position), label);
            }
        }
    }

    private static JsonNode outTemporal(CallableStatement call, int position, Class<?> type)
            throws SQLException {
        Object value;
        try {
            value = call.getObject(position, type);
        } catch (SQLException | RuntimeException unsupported) {
            value = call.getString(position);
        }
        return value == null ? NODES.nullNode() : NODES.stringNode(value.toString());
    }

    /**
     * Convert a stored-procedure {@code OUT} value read with {@code getObject}, for the types
     * {@link #out} does not read directly. A cursor ({@link ResultSet}) becomes the array of its
     * rows and is closed.
     *
     * @throws SQLException when a large object or a cursor cannot be read
     * @throws SqlClientException when the value is binary
     */
    static JsonNode value(Object value, String label) throws SQLException {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof ResultSet cursor) {
            try (cursor) {
                return read(cursor).get(ROWS);
            }
        }
        if (value instanceof byte[] || value instanceof java.sql.Blob) {
            throw new SqlClientException("out parameter '" + label
                    + "' is binary, which SQL procedures do not return yet");
        }
        if (value instanceof BigDecimal decimal) {
            return NODES.numberNode(decimal);
        }
        if (value instanceof java.math.BigInteger integer) {
            return NODES.numberNode(integer);
        }
        if (value instanceof Double || value instanceof Float) {
            return NODES.numberNode(((Number) value).doubleValue());
        }
        if (value instanceof Number number) {
            return NODES.numberNode(number.longValue());
        }
        if (value instanceof Boolean bool) {
            return NODES.booleanNode(bool);
        }
        if (value instanceof java.sql.Date date) {
            return NODES.stringNode(date.toLocalDate().toString());
        }
        if (value instanceof java.sql.Time time) {
            return NODES.stringNode(time.toLocalTime().toString());
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return NODES.stringNode(timestamp.toLocalDateTime().toString());
        }
        if (value instanceof java.sql.Clob clob) {
            try {
                return NODES.stringNode(clob.getSubString(1, (int) clob.length()));
            } finally {
                clob.free();
            }
        }
        return NODES.stringNode(value.toString());
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
