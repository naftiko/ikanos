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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Outcome;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * {@link SqlClientAdapter} against an in-process HSQLDB database, one per test.
 */
class SqlClientAdapterTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private String url;
    private Connection keepAlive;
    private SqlClientAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:hsqldb:mem:" + UUID.randomUUID();
        keepAlive = DriverManager.getConnection(url, "SA", "");
        try (Statement ddl = keepAlive.createStatement()) {
            ddl.execute("""
                    create table customers (
                      id varchar(10) primary key, name varchar(50), balance decimal(10,2),
                      active boolean, updated_at timestamp, photo varbinary(16))""");
            ddl.execute("insert into customers values ('c1', 'Acme', 1250.10, true,"
                    + " timestamp '2026-11-04 09:12:00', null)");
            ddl.execute("insert into customers values ('c2', 'Globex', 10.00, false,"
                    + " timestamp '2026-11-05 10:00:00', null)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (adapter != null) {
            adapter.stop();
        }
        try (Statement shutdown = keepAlive.createStatement()) {
            shutdown.execute("shutdown");
        }
        keepAlive.close();
    }

    private SqlClientSpec spec(String procedures) throws Exception {
        return (SqlClientSpec) YAML.readValue("""
                type: sql
                namespace: erp
                connection:
                  url: "%s"
                  username: SA
                  password: ""
                procedures:
                %s
                """.formatted(url, procedures.indent(2)), ClientSpec.class);
    }

    private SqlClientAdapter started(String procedures) throws Exception {
        adapter = new SqlClientAdapter(null, spec(procedures));
        adapter.start();
        return adapter;
    }

    private static final String GET_CUSTOMER = """
            get-customer:
              description: One customer by id.
              statement: select id, name, balance, active, updated_at from customers where id = {{customer-id}}
              inputParameters:
                customer-id: { type: string }
              outputParameters:
                name: { type: string, value: "$.rows[0].name" }
            """;

    private ConsumedResult call(String procedure, Map<String, Object> parameters)
            throws Exception {
        ConsumedInvocation invocation = adapter.prepare(procedure, parameters);
        return invocation.invoke();
    }

    @Test
    void invokeShouldReturnRowsAsAJsonDocument() throws Exception {
        started(GET_CUSTOMER);

        ConsumedResult result = call("get-customer", Map.of("customer-id", "c1"));

        assertEquals(200, result.status());
        JsonNode document = result.document();
        assertEquals(1, document.get("rowCount").asInt());
        JsonNode row = document.get("rows").get(0);
        assertEquals("Acme", row.get("NAME").asString());
        assertEquals(new BigDecimal("1250.10"), row.get("BALANCE").decimalValue());
        assertTrue(row.get("ACTIVE").asBoolean());
        assertEquals("2026-11-04T09:12", row.get("UPDATED_AT").asString());
    }

    @Test
    void invokeShouldReturnNoRowsWhenNothingMatches() throws Exception {
        started(GET_CUSTOMER);

        ConsumedResult result = call("get-customer", Map.of("customer-id", "nobody"));

        assertEquals(0, result.document().get("rowCount").asInt());
        assertTrue(result.document().get("rows").isEmpty());
    }

    @Test
    void invokeShouldKeepColumnLabelsAsAliased() throws Exception {
        started("""
                list:
                  description: All customers.
                  statement: select id as "id", name as "name" from customers order by id
                """);

        ConsumedResult result = call("list", Map.of());

        assertEquals("c2", result.document().get("rows").get(1).get("id").asString());
    }

    @Test
    void invokeShouldConvertTextToTheDeclaredSqlType() throws Exception {
        started("""
                changed-since:
                  description: Customers changed since a time.
                  statement: select id from customers where updated_at >= {{since}} order by id
                  inputParameters:
                    since: { type: string, sqlType: TIMESTAMP }
                """);

        ConsumedResult result = call("changed-since", Map.of("since", "2026-11-05T00:00:00Z"));

        assertEquals(1, result.document().get("rowCount").asInt());
    }

    @Test
    void invokeShouldBindAnAbsentOptionalInputAsNull() throws Exception {
        started("""
                by-name:
                  description: Customers by optional name.
                  statement: select id from customers where ({{name}} is null or name = {{name}})
                  inputParameters:
                    name: { type: string, required: false, sqlType: VARCHAR }
                """);

        ConsumedResult result = call("by-name", new HashMap<>());

        assertEquals(2, result.document().get("rowCount").asInt());
    }

    @Test
    void invokeShouldRefuseABinaryColumnNamingIt() throws Exception {
        started("""
                photo:
                  description: A binary column.
                  statement: select photo from customers
                """);

        ConsumedResult result = call("photo", Map.of());

        assertEquals(502, result.status());
        assertTrue(result.failureCause().getMessage().contains("PHOTO"));
    }

    @Test
    void invokeShouldMapADataExceptionTo400WithoutBody() throws Exception {
        started("""
                cast:
                  description: Casts its input.
                  statement: select cast({{value}} as integer) as v from customers
                  inputParameters:
                    value: { type: string }
                """);

        ConsumedResult result = call("cast", Map.of("value", "not-a-number"));

        assertEquals(400, result.status());
        assertEquals(Outcome.CLIENT_ERROR, result.outcome());
        assertFalse(result.hasBody());
        assertInstanceOf(SQLException.class, result.failureCause());
    }

    @Test
    void invokeShouldMapAMissingTableTo502() throws Exception {
        started("""
                missing:
                  description: Reads a table that does not exist.
                  statement: select * from no_such_table
                """);

        ConsumedResult result = call("missing", Map.of());

        assertEquals(502, result.status());
        assertEquals(Outcome.UPSTREAM_ERROR, result.outcome());
    }

    @Test
    void failureCauseShouldCarryTheSqlStateButNotTheDriverMessage() throws Exception {
        started("""
                missing:
                  description: Reads a table that does not exist.
                  statement: select * from no_such_table
                """);

        ConsumedResult result = call("missing", Map.of());

        SQLException cause = assertInstanceOf(SQLException.class, result.failureCause());
        assertFalse(cause.getMessage().toUpperCase().contains("NO_SUCH_TABLE"),
                cause.getMessage());
        assertNull(cause.getCause());
        assertTrue(cause.getSQLState() != null && !cause.getSQLState().isEmpty());
    }

    @Test
    void bindShouldKeepTheScaleOfADecimal() throws Exception {
        java.sql.PreparedStatement statement =
                org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        io.ikanos.spec.InputParameterSpec input =
                new io.ikanos.spec.InputParameterSpec("amount", "number", null, null);

        SqlBind.of(input, "1.25", "erp.find", 1).bind(statement);

        org.mockito.Mockito.verify(statement).setBigDecimal(1, new BigDecimal("1.25"));
    }

    @Test
    void invokeShouldFailWhenTheAdapterIsNotStarted() throws Exception {
        adapter = new SqlClientAdapter(null, spec(GET_CUSTOMER));
        ConsumedInvocation invocation = adapter.prepare("get-customer", Map.of("customer-id", "c1"));

        assertThrows(IllegalStateException.class, invocation::invoke);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "jdbc:mysql://reader:s3cret@db/erp",
        "jdbc:oracle:thin:scott/s3cret@db:1521/erp"
    })
    void startShouldNotShowTheUrlWhenNoDriverAcceptsIt(String noDriverUrl) throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setUrl(noDriverUrl);
        adapter = new SqlClientAdapter(null, spec);

        IllegalStateException e = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(e.getMessage().contains("no JDBC driver accepts connection.url"),
                e.getMessage());
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains("s3cret"), t.toString());
        }
    }

    @Test
    void invokeShouldAskForAReadOnlyConnection() throws Exception {
        started(GET_CUSTOMER);
        SqlClientAdapter recording = org.mockito.Mockito.spy(adapter);
        Connection connection =
                org.mockito.Mockito.spy(DriverManager.getConnection(url, "SA", ""));
        com.zaxxer.hikari.HikariDataSource dataSource =
                org.mockito.Mockito.mock(com.zaxxer.hikari.HikariDataSource.class);
        org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
        org.mockito.Mockito.doReturn(dataSource).when(recording).dataSource();

        recording.prepare("get-customer", Map.of("customer-id", "c1")).invoke();

        org.mockito.Mockito.verify(connection).setReadOnly(true);
    }

    @Test
    void prepareShouldReturnNullForAnUndeclaredProcedure() throws Exception {
        started(GET_CUSTOMER);

        assertNull(adapter.prepare("drop-everything", Map.of()));
        assertFalse(adapter.declaresOperation("drop-everything"));
        assertTrue(adapter.declaresOperation("get-customer"));
    }

    @Test
    void prepareShouldRefuseAMissingRequiredInput() throws Exception {
        started(GET_CUSTOMER);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("get-customer", Map.of()));

        assertTrue(e.getMessage().contains("customer-id"), e.getMessage());
    }

    @Test
    void prepareShouldRefuseAValueThatIsNotTheDeclaredTypeWithoutEchoingIt() throws Exception {
        started("""
                by-balance:
                  description: Customers above a balance.
                  statement: select id from customers where balance > {{min}}
                  inputParameters:
                    min: { type: number }
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("by-balance", Map.of("min", "lots")));

        assertFalse(e.getMessage().contains("lots"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAStatementThatWrites() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        wipe:
                          description: Not a read.
                          statement: delete from customers
                        """)));

        assertTrue(e.getMessage().contains("must read"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAPlaceholderWithoutAnInput() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        get:
                          description: Typo in the placeholder.
                          statement: select * from customers where id = {{cust-id}}
                          inputParameters:
                            customer-id: { type: string }
                        """)));

        assertTrue(e.getMessage().contains("{{cust-id}}"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnUnboundConnectionPlaceholder() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setPassword("{{DB_PASSWORD}}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec));

        assertTrue(e.getMessage().contains("DB_PASSWORD"), e.getMessage());
    }

    @Test
    void statusShouldMapSqlStatesToHttpEquivalents() {
        assertEquals(400, SqlConsumedResult.status(new SQLException("x", "22003")));
        assertEquals(409, SqlConsumedResult.status(new SQLException("x", "23505")));
        assertEquals(504, SqlConsumedResult.status(new SQLTimeoutException("x")));
        assertEquals(504, SqlConsumedResult.status(new SQLException("x", "57014")));
        assertEquals(502, SqlConsumedResult.status(new SQLException("x", "08006")));
        assertEquals(502, SqlConsumedResult.status(new SQLException("x", "42P01")));
        assertEquals(502, SqlConsumedResult.status(new SQLException("x")));
    }

    @Test
    void statusShouldMapAPoolTimeoutTo503UnlessTheDatabaseIsUnreachable() {
        assertEquals(503, SqlConsumedResult.status(new SQLTransientConnectionException("x")));
        assertEquals(502,
                SqlConsumedResult.status(new SQLTransientConnectionException("x", "08001")));
    }

    @Test
    void timeoutSecondsShouldRoundUpAndDefaultToThirty() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);

        assertEquals(30, SqlClientAdapter.timeoutSeconds(spec.getProcedures().get("get-customer")));
        spec.getProcedures().get("get-customer").setTimeoutMs(1500);
        assertEquals(2, SqlClientAdapter.timeoutSeconds(spec.getProcedures().get("get-customer")));
    }

    @Test
    void invokeShouldRefuseTwoColumnsWithTheSameLabel() throws Exception {
        started("""
                pair:
                  description: Two columns named ID.
                  statement: select a.id, b.id from customers a, customers b where a.id = 'c1' and b.id = 'c2'
                """);

        ConsumedResult result = call("pair", Map.of());

        assertEquals(502, result.status());
    }

    @Test
    void constructorShouldRefuseAnUnknownSqlType() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        get:
                          description: Typo in the sqlType.
                          statement: select * from customers where id = {{id}}
                          inputParameters:
                            id: { type: string, sqlType: VARCHARR }
                        """)));

        assertTrue(e.getMessage().contains("VARCHARR"), e.getMessage());
    }

    @Test
    void poolConfigShouldMakePostgresqlEnforceReadOnlyInAutocommit() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setUrl("jdbc:postgresql://db:5432/erp");

        HikariConfig config = new SqlClientAdapter(null, spec).poolConfig();

        assertEquals("always", config.getDataSourceProperties().get("readOnlyMode"));
    }

    @Test
    void poolConfigShouldKeepAReadOnlyModeTheOperatorSets() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setUrl("jdbc:postgresql://db:5432/erp?readOnlyMode=transaction");

        HikariConfig config = new SqlClientAdapter(null, spec).poolConfig();

        assertNull(config.getDataSourceProperties().get("readOnlyMode"));
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:mysql://reader:s3cret@db:3306/erp, jdbc:mysql://db:3306/erp",
        "jdbc:mariadb://reader:s3cret@db:3306/erp, jdbc:mariadb://db:3306/erp",
        "jdbc:postgresql://reader:s3cret@db:5432/erp, jdbc:postgresql://db:5432/erp",
        "jdbc:oracle:thin:reader/s3cret@//db:1521/erp, jdbc:oracle:thin:@//db:1521/erp"})
    void poolConfigShouldHandHikariAUrlWithoutItsUserInfo(String url, String expected)
            throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setUrl(url);
        spec.getConnection().setUsername(null);
        spec.getConnection().setPassword(null);

        HikariConfig config = new SqlClientAdapter(null, spec).poolConfig();

        assertEquals(expected, config.getJdbcUrl());
        assertEquals("reader", config.getUsername());
        assertEquals("s3cret", config.getPassword());
    }

    @Test
    void poolConfigShouldKeepTheUsernameAndPasswordTheCapabilitySets() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setUrl("jdbc:mysql://reader:s3cret@db:3306/erp");
        spec.getConnection().setUsername("app");
        spec.getConnection().setPassword("t0ken");

        HikariConfig config = new SqlClientAdapter(null, spec).poolConfig();

        assertEquals("jdbc:mysql://db:3306/erp", config.getJdbcUrl());
        assertEquals("app", config.getUsername());
        assertEquals("t0ken", config.getPassword());
    }

    @Test
    void poolConfigShouldResolveDriverPropertiesFromBindings() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setProperties(Map.of("password", "{{DB_PASSWORD}}"));
        io.ikanos.Capability capability = org.mockito.Mockito.mock(io.ikanos.Capability.class);
        org.mockito.Mockito.when(capability.getBindings())
                .thenReturn(Map.of("DB_PASSWORD", "s3cret"));

        HikariConfig config = new SqlClientAdapter(capability, spec).poolConfig();

        assertEquals("s3cret", config.getDataSourceProperties().get("password"));
    }

    @Test
    void poolConfigShouldKeepABoundPasswordThatContainsBraces() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setPassword("{{DB_PASSWORD}}");
        io.ikanos.Capability capability = org.mockito.Mockito.mock(io.ikanos.Capability.class);
        org.mockito.Mockito.when(capability.getBindings())
                .thenReturn(Map.of("DB_PASSWORD", "pa{{ss}}word"));

        HikariConfig config = new SqlClientAdapter(capability, spec).poolConfig();

        assertEquals("pa{{ss}}word", config.getPassword());
    }

    @Test
    void constructorShouldRefuseAMalformedConnectionPlaceholder() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setPassword("{{DB_PASSWORD");

        assertThrows(IllegalArgumentException.class, () -> new SqlClientAdapter(null, spec));
    }

    @Test
    void constructorShouldRefuseAnUnboundDriverProperty() throws Exception {
        SqlClientSpec spec = spec(GET_CUSTOMER);
        spec.getConnection().setProperties(Map.of("password", "{{DB_PASSWORD}}"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec));

        assertTrue(e.getMessage().contains("connection.properties.password"), e.getMessage());
    }

    @Test
    void bindShouldTypeAnAbsentStringInputAsVarchar() throws Exception {
        java.sql.PreparedStatement statement =
                org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        io.ikanos.spec.InputParameterSpec input =
                new io.ikanos.spec.InputParameterSpec("name", "string", null, null);

        SqlBind.of(input, null, "erp.find", 1).bind(statement);

        org.mockito.Mockito.verify(statement).setNull(1, java.sql.Types.VARCHAR);
    }

    @Test
    void constructorShouldRefuseAPlaceholderInsideAStringLiteral() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        search:
                          description: A placeholder that would be sent as text.
                          statement: select id from customers where name like '%{{text}}%'
                          inputParameters:
                            text: { type: string }
                        """)));

        assertTrue(e.getMessage().contains("{{text}} inside a string literal"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAMustacheSection() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        find:
                          description: A section is not a placeholder.
                          statement: select id from customers {{#id}}where id = {{id}}{{/id}}
                          inputParameters:
                            id: { type: string }
                        """)));

        assertTrue(e.getMessage().contains("Mustache '#' tag"), e.getMessage());
    }

    @Test
    void invokeShouldBindAValueThatLooksLikeAPlaceholderAsText() throws Exception {
        started(GET_CUSTOMER);

        ConsumedResult result = call("get-customer", Map.of("customer-id", "{{customer-id}}"));

        assertEquals(0, result.document().get("rowCount").asInt());
    }

    @Test
    void columnShouldReadAnUnsignedBigintPastLongMaxValue() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        org.mockito.Mockito.when(meta.getColumnType(1)).thenReturn(java.sql.Types.BIGINT);
        org.mockito.Mockito.when(meta.isSigned(1)).thenReturn(false);
        org.mockito.Mockito.when(rs.getBigDecimal(1))
                .thenReturn(new BigDecimal("18446744073709551615"));

        JsonNode value = SqlRowDocument.column(rs, meta, 1, "ID");

        assertEquals("18446744073709551615", value.toString());
    }

    @Test
    void columnShouldKeepTheDecimalsOfAJsonColumn() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        org.mockito.Mockito.when(meta.getColumnType(1)).thenReturn(java.sql.Types.OTHER);
        org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("jsonb");
        org.mockito.Mockito.when(rs.getString(1)).thenReturn("{\"total\":12345678901234567890.12}");

        JsonNode value = SqlRowDocument.column(rs, meta, 1, "PAYLOAD");

        assertEquals(new BigDecimal("12345678901234567890.12"), value.get("total").decimalValue());
    }

    @Test
    void columnShouldReadAPostgresqlTimestamptzWithItsOffset() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        // pgjdbc reports timestamptz as TIMESTAMP and refuses it as a LocalDateTime
        org.mockito.Mockito.when(meta.getColumnType(1)).thenReturn(java.sql.Types.TIMESTAMP);
        org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("timestamptz");
        org.mockito.Mockito.when(rs.getObject(1, java.time.LocalDateTime.class))
                .thenThrow(new SQLException("Cannot convert the column of type TIMESTAMPTZ"));
        org.mockito.Mockito.when(rs.getObject(1, java.time.OffsetDateTime.class))
                .thenReturn(java.time.OffsetDateTime.parse("2026-11-17T09:00:00Z"));
        org.mockito.Mockito.when(rs.getString(1)).thenReturn("2026-11-17 10:00:00+01");

        JsonNode value = SqlRowDocument.column(rs, meta, 1, "CREATED_AT");

        assertEquals("2026-11-17T09:00Z", value.asString());
    }

    @Test
    void columnShouldReadAPostgresqlTimetzWithItsOffset() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        // pgjdbc reports timetz as TIME and refuses it as a LocalTime
        org.mockito.Mockito.when(meta.getColumnType(1)).thenReturn(java.sql.Types.TIME);
        org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("timetz");
        org.mockito.Mockito.when(rs.getObject(1, java.time.LocalTime.class))
                .thenThrow(new SQLException("Cannot convert the column of type TIMETZ"));
        org.mockito.Mockito.when(rs.getObject(1, java.time.OffsetTime.class))
                .thenReturn(java.time.OffsetTime.parse("09:00:00+02:00"));
        org.mockito.Mockito.when(rs.getString(1)).thenReturn("09:00:00+02");

        JsonNode value = SqlRowDocument.column(rs, meta, 1, "OPENS_AT");

        assertEquals("09:00+02:00", value.asString());
    }

    @Test
    void columnShouldKeepAnInfiniteTimestamptzAsInfinity() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        org.mockito.Mockito.when(meta.getColumnType(1)).thenReturn(java.sql.Types.TIMESTAMP);
        org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("timestamptz");
        // pgjdbc reads 'infinity'::timestamptz as OffsetDateTime.MAX
        org.mockito.Mockito.when(rs.getObject(1, java.time.OffsetDateTime.class))
                .thenReturn(java.time.OffsetDateTime.MAX);

        JsonNode value = SqlRowDocument.column(rs, meta, 1, "VALID_UNTIL");

        assertEquals("infinity", value.asString());
    }

    @Test
    void columnShouldKeepAnInfiniteDateAsInfinity() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        org.mockito.Mockito.when(meta.getColumnType(1)).thenReturn(java.sql.Types.DATE);
        org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("date");
        // pgjdbc reads 'infinity'::date and '-infinity'::date as LocalDate.MAX and MIN
        org.mockito.Mockito.when(rs.getObject(1, java.time.LocalDate.class))
                .thenReturn(java.time.LocalDate.MAX, java.time.LocalDate.MIN);

        assertEquals("infinity", SqlRowDocument.column(rs, meta, 1, "VALID_UNTIL").asString());
        assertEquals("-infinity", SqlRowDocument.column(rs, meta, 1, "VALID_UNTIL").asString());
    }

    @Test
    void poolFailureShouldNameADriverClassThatRefusesTheUrl() {
        String reason = SqlClientAdapter.poolFailure(new RuntimeException(
                "Driver org.hsqldb.jdbc.JDBCDriver claims to not accept jdbcUrl, jdbc:x://a:b@db"));

        assertEquals("the connection.driverClassName driver does not accept connection.url",
                reason);
    }
}
