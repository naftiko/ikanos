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
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.InputParameterSpec;

/**
 * One value bound to a {@code ?} of a prepared statement: the converted value and the JDBC type it
 * is bound as.
 *
 * <p>Values arrive from steps as text, numbers or booleans. {@link #of} converts text to the
 * declared {@code sqlType} (or, without one, to the input's {@code integer}, {@code number} or
 * {@code boolean} type) before binding, so the driver never guesses from a string. A conversion
 * failure names the input, never the value.</p>
 *
 * @param value the converted value, or {@code null} for SQL {@code NULL}
 * @param jdbcType the {@link Types} code to bind with, or {@code null} to let the driver infer it
 */
record SqlBind(Object value, Integer jdbcType) {

    /**
     * Convert a step value for one input.
     *
     * @param input the declared input
     * @param raw the value from the step, may be null
     * @param where {@code <namespace>.<procedure>}, for error messages
     * @throws IllegalArgumentException when the value cannot be converted
     */
    static SqlBind of(InputParameterSpec input, Object raw, String where) {
        JDBCType target = targetType(input, where);
        Object value = unwrap(raw);
        if (value == null) {
            return new SqlBind(null, target != null ? target.getVendorTypeNumber() : null);
        }
        if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
            throw new IllegalArgumentException("Input '" + input.getName() + "' of SQL procedure "
                    + where + " must be a single value, not a list or an object");
        }
        if (target == null) {
            return new SqlBind(value, null);
        }
        try {
            return new SqlBind(convert(value, target), target.getVendorTypeNumber());
        } catch (NumberFormatException | DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("Input '" + input.getName()
                    + "' of SQL procedure " + where + " is not a valid " + target.getName());
        }
    }

    /** Bind this value at a position of a prepared statement. */
    void bind(PreparedStatement statement, int position) throws SQLException {
        if (value == null) {
            statement.setNull(position, jdbcType != null ? jdbcType : Types.NULL);
        } else if (jdbcType == null) {
            statement.setObject(position, value);
        } else {
            statement.setObject(position, value, jdbcType);
        }
    }

    static JDBCType targetType(InputParameterSpec input, String where) {
        String sqlType = input.getSqlType();
        if (sqlType != null && !sqlType.isBlank()) {
            try {
                return JDBCType.valueOf(sqlType.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Input '" + input.getName()
                        + "' of SQL procedure " + where + " declares unknown sqlType '" + sqlType
                        + "'");
            }
        }
        String type = input.getType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "integer" -> JDBCType.BIGINT;
            case "number" -> JDBCType.NUMERIC;
            case "boolean" -> JDBCType.BOOLEAN;
            default -> null;
        };
    }

    private static Object unwrap(Object raw) {
        if (raw instanceof JsonNode node) {
            if (node.isNull() || node.isMissingNode()) {
                return null;
            }
            if (node.isNumber()) {
                return node.numberValue();
            }
            if (node.isBoolean()) {
                return node.booleanValue();
            }
            if (node.isString()) {
                return node.stringValue();
            }
            return node.isArray() ? java.util.List.of(node) : Map.of("", node);
        }
        return raw;
    }

    private static Object convert(Object value, JDBCType target) {
        String text = value instanceof String s ? s.trim() : null;
        return switch (target) {
            case TINYINT, SMALLINT, INTEGER, BIGINT -> text != null ? Long.valueOf(text)
                    : value instanceof Number n ? exactLong(n) : value;
            case NUMERIC, DECIMAL -> text != null ? new BigDecimal(text)
                    : value instanceof Number n ? new BigDecimal(n.toString()) : value;
            case REAL, FLOAT, DOUBLE -> text != null ? Double.valueOf(text)
                    : value instanceof Number n ? n.doubleValue() : value;
            case BOOLEAN, BIT -> text != null ? parseBoolean(text) : value;
            case DATE -> text != null ? LocalDate.parse(text) : value;
            case TIME -> text != null ? LocalTime.parse(text) : value;
            case TIME_WITH_TIMEZONE -> text != null ? OffsetTime.parse(text) : value;
            case TIMESTAMP -> text != null ? parseTimestamp(text) : value;
            case TIMESTAMP_WITH_TIMEZONE -> text != null ? parseOffsetTimestamp(text) : value;
            case CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR, CLOB, NCLOB ->
                    value.toString();
            default -> value;
        };
    }

    private static long exactLong(Number n) {
        return new BigDecimal(n.toString()).longValueExact();
    }

    private static Boolean parseBoolean(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new NumberFormatException("not a boolean");
    }

    /** A timestamp with an offset ({@code …Z}) is converted to UTC; one without is taken as is. */
    private static LocalDateTime parseTimestamp(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException withOffset) {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        }
    }

    /** A timestamp without an offset is taken as UTC. */
    private static OffsetDateTime parseOffsetTimestamp(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(text).atOffset(ZoneOffset.UTC);
        }
    }
}
