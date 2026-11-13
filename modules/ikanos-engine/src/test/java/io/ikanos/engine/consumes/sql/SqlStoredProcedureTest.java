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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Stored procedures ({@code storedProcedure:}) on {@link SqlClientAdapter}, against an
 * in-process HSQLDB database, one per test.
 */
class SqlStoredProcedureTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private String url;
    private Connection keepAlive;
    private SqlClientAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:hsqldb:mem:" + UUID.randomUUID();
        keepAlive = DriverManager.getConnection(url, "SA", "");
        try (Statement ddl = keepAlive.createStatement()) {
            ddl.execute("create table accounts (id varchar(10) primary key,"
                    + " balance decimal(10,2), currency varchar(3), active boolean)");
            ddl.execute("insert into accounts values ('a1', 1250.00, 'EUR', true)");
            ddl.execute("insert into accounts values ('a2', 10.50, 'USD', false)");
            ddl.execute("insert into accounts values ('a3', 99.00, 'EUR', true)");
            ddl.execute("""
                    create procedure get_balance(in acct varchar(10),
                      out bal decimal(10,2), out cur varchar(3))
                    reads sql data
                    begin atomic
                      select balance, currency into bal, cur from accounts where id = acct;
                    end""");
            ddl.execute("""
                    create procedure active_accounts()
                    reads sql data dynamic result sets 1
                    begin atomic
                      declare result cursor with return for
                        select id from accounts where active = true order by id;
                      open result;
                    end""");
            ddl.execute("""
                    create procedure double_it(inout n integer)
                    contains sql
                    begin atomic
                      set n = n * 2;
                    end""");
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
                namespace: billing
                connection:
                  url: "%s"
                  username: SA
                  password: ""
                procedures:
                %s
                """.formatted(url, procedures.indent(2)), ClientSpec.class);
    }

    private ConsumedResult call(String procedures, String name, Map<String, Object> parameters)
            throws Exception {
        adapter = new SqlClientAdapter(null, spec(procedures));
        adapter.start();
        return adapter.prepare(name, parameters).invoke();
    }

    private static final String GET_BALANCE = """
            get-account-balance:
              description: Balance of one account.
              storedProcedure: get_balance
              readOnly: true
              inputParameters:
                account-id: { type: string, position: 1 }
              outParameters:
                balance: { position: 2, sqlType: DECIMAL }
                currency: { position: 3, sqlType: VARCHAR }
              outputParameters:
                balance: { type: number, value: "$.out.balance" }
            """;

    @Test
    void invokeShouldReturnOutValuesUnderOut() throws Exception {
        ConsumedResult result = call(GET_BALANCE, "get-account-balance",
                Map.of("account-id", "a1"));

        assertEquals(200, result.status());
        JsonNode out = result.document().get("out");
        assertEquals(new BigDecimal("1250.00"), out.get("balance").decimalValue());
        assertEquals("EUR", out.get("currency").asString());
    }

    @Test
    void invokeShouldReturnTheFirstResultSetAsRows() throws Exception {
        ConsumedResult result = call("""
                active-accounts:
                  description: Active accounts.
                  storedProcedure: active_accounts
                  readOnly: true
                """, "active-accounts", Map.of());

        assertEquals(200, result.status());
        assertEquals(2, result.document().get("rowCount").asInt(), result.document().toString());
        assertEquals("a3", result.document().get("rows").get(1).get("ID").asString());
    }

    @Test
    void invokeShouldBindAndReadAnInoutParameterAtOnePosition() throws Exception {
        ConsumedResult result = call("""
                double-it:
                  description: Doubles a number.
                  storedProcedure: PUBLIC.double_it
                  readOnly: true
                  inputParameters:
                    n: { type: integer, position: 1, sqlType: INTEGER }
                  outParameters:
                    n: { position: 1, sqlType: INTEGER }
                """, "double-it", Map.of("n", 21));

        assertEquals(42, result.document().get("out").get("n").asInt());
    }

    @Test
    void constructorShouldRequireReadOnlyOnAStoredProcedure() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace("  readOnly: true\n",
                        ""))));

        assertTrue(e.getMessage().contains("readOnly"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseReadOnlyFalseUntilWritesAreSupported() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace("readOnly: true",
                        "readOnly: false"))));

        assertTrue(e.getMessage().contains("not supported yet"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAGapInPositions() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace("position: 3",
                        "position: 4"))));

        assertTrue(e.getMessage().contains("position 3"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseTwoOutParametersAtOnePosition() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace("position: 3",
                        "position: 2"))));

        assertTrue(e.getMessage().contains("two out parameters"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseAnInputWithoutAPosition() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace(
                        "{ type: string, position: 1 }", "{ type: string }"))));

        assertTrue(e.getMessage().contains("account-id"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseANameThatIsNotAnIdentifier() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace(
                        "storedProcedure: get_balance",
                        "storedProcedure: \"get_balance(?); drop table accounts; --\""))));

        assertFalse(e.getMessage().contains("drop table"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseOutParametersOnAStatement() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        balance:
                          description: Statements have no OUT parameters.
                          statement: select balance from accounts
                          outParameters:
                            balance: { position: 1, sqlType: DECIMAL }
                        """)));

        assertTrue(e.getMessage().contains("outParameters"), e.getMessage());
    }

    @Test
    void constructorShouldRefuseBothAStatementAndAStoredProcedure() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec(GET_BALANCE.replace(
                        "readOnly: true", "readOnly: true\n  statement: select 1"))));

        assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
    }

    @Test
    void invokeShouldReadAnOutValueByItsDeclaredType() throws Exception {
        try (Statement ddl = keepAlive.createStatement()) {
            ddl.execute("""
                    create procedure audit_stamp(out ts timestamp, out ok boolean, out n bigint)
                    contains sql
                    begin atomic
                      set ts = timestamp '2026-11-04 09:12:30';
                      set ok = true;
                      set n = 9007199254740993;
                    end""");
        }

        ConsumedResult result = call("""
                audit-stamp:
                  description: A timestamp, a flag and a large integer.
                  storedProcedure: audit_stamp
                  readOnly: true
                  outParameters:
                    ts: { position: 1, sqlType: TIMESTAMP }
                    ok: { position: 2, sqlType: BOOLEAN }
                    n: { position: 3, sqlType: BIGINT }
                """, "audit-stamp", Map.of());

        JsonNode out = result.document().get("out");
        assertEquals("2026-11-04T09:12:30", out.get("ts").asString());
        assertTrue(out.get("ok").booleanValue());
        assertEquals(9007199254740993L, out.get("n").longValue());
    }

    @Test
    void valueShouldReadACursorIntoRowsAndCloseIt() throws Exception {
        try (Statement query = keepAlive.createStatement()) {
            ResultSet cursor = query.executeQuery("select id from accounts order by id");

            JsonNode rows = SqlRowDocument.value(cursor, "accounts");

            assertEquals(3, rows.size());
            assertEquals("a1", rows.get(0).get("ID").asString());
            assertTrue(cursor.isClosed());
        }
    }

    @Test
    void invokeShouldBindTheInputHalfOfAnInoutParameterAsItsOutType() throws Exception {
        ConsumedResult result = call("""
                double-it:
                  description: Doubles a number given as text.
                  storedProcedure: double_it
                  readOnly: true
                  inputParameters:
                    n: { type: string, position: 1 }
                  outParameters:
                    n: { position: 1, sqlType: INTEGER }
                """, "double-it", Map.of("n", "21"));

        assertEquals(42, result.document().get("out").get("n").intValue());
    }

    @Test
    void constructorShouldRefuseAnInoutParameterWithTwoTypes() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SqlClientAdapter(null, spec("""
                        double-it:
                          description: Two types at one position.
                          storedProcedure: double_it
                          readOnly: true
                          inputParameters:
                            n: { type: string, position: 1, sqlType: VARCHAR }
                          outParameters:
                            n: { position: 1, sqlType: INTEGER }
                        """)));

        assertTrue(e.getMessage().contains("one type"), e.getMessage());
    }

    @Test
    void readShouldKeepATransactionOpenWhileItReadsACursorOut() throws Exception {
        SqlClientSpec spec = spec("""
                open-orders:
                  description: Open orders, as a cursor.
                  storedProcedure: open_orders
                  readOnly: true
                  outParameters:
                    orders: { position: 1, sqlType: OTHER }
                """);
        adapter = new SqlClientAdapter(null, spec);
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        java.sql.CallableStatement call = org.mockito.Mockito.mock(java.sql.CallableStatement.class);
        org.mockito.Mockito.when(connection.prepareCall(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(call);
        org.mockito.Mockito.when(call.getUpdateCount()).thenReturn(-1);

        ConsumedResult result = adapter.read(connection,
                SqlClientAdapter.buildProcedures(spec, SqlStatement.Dialect.STANDARD).get("open-orders"),
                java.util.List.of());

        assertEquals(200, result.status());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(connection, call);
        order.verify(connection).setAutoCommit(false);
        order.verify(call).execute();
        order.verify(call).getObject(1);
        order.verify(connection).rollback();
    }

    @Test
    void readShouldStayInAutoCommitWithoutACursorOut() throws Exception {
        SqlClientSpec spec = spec(GET_BALANCE);
        adapter = new SqlClientAdapter(null, spec);
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        java.sql.CallableStatement call = org.mockito.Mockito.mock(java.sql.CallableStatement.class);
        org.mockito.Mockito.when(connection.prepareCall(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(call);
        org.mockito.Mockito.when(call.getUpdateCount()).thenReturn(-1);

        adapter.read(connection, SqlClientAdapter.buildProcedures(spec, SqlStatement.Dialect.STANDARD)
                .get("get-account-balance"), java.util.List.of());

        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).setAutoCommit(false);
    }
}
