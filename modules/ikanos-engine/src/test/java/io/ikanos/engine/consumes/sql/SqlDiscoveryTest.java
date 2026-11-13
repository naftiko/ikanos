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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.sql.SqlClientSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * The start-up check ({@code discovery}) of {@link SqlClientAdapter}, against an in-process HSQLDB
 * database, one per test.
 */
class SqlDiscoveryTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private String url;
    private Connection keepAlive;
    private SqlClientAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:hsqldb:mem:" + UUID.randomUUID();
        keepAlive = DriverManager.getConnection(url, "SA", "");
        try (Statement ddl = keepAlive.createStatement()) {
            ddl.execute("create table customers (id varchar(10) primary key, name varchar(50))");
            ddl.execute("""
                    create procedure get_name(in cid varchar(10), out cname varchar(50))
                    reads sql data
                    begin atomic
                      select name into cname from customers where id = cid;
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

    private SqlClientSpec spec(String jdbcUrl, String discovery, String procedures)
            throws Exception {
        return (SqlClientSpec) YAML.readValue("""
                type: sql
                namespace: erp
                discovery: %s
                connection:
                  url: "%s"
                  username: SA
                  password: ""
                pool:
                  connectionTimeoutMs: 250
                procedures:
                %s
                """.formatted(discovery, jdbcUrl, procedures.indent(2)), ClientSpec.class);
    }

    private static final String VALID = """
            get-customer:
              description: One customer.
              statement: select id, name from customers where id = :id
              inputParameters:
                id: { type: string }
            get-name:
              description: Name of one customer.
              storedProcedure: get_name
              readOnly: true
              inputParameters:
                id: { type: string, position: 1 }
              outParameters:
                name: { position: 2, sqlType: VARCHAR }
            """;

    @Test
    void startShouldPassWhenEveryProcedureExists() throws Exception {
        adapter = new SqlClientAdapter(null, spec(url, "verify", VALID));

        assertDoesNotThrow(adapter::start);
    }

    @Test
    void startShouldFailOnAMisspelledTableNamingTheProcedureNotTheSql() throws Exception {
        adapter = new SqlClientAdapter(null, spec(url, "verify", VALID.replace(
                "from customers where id", "from custommers where id")));

        IllegalStateException e = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(e.getMessage().contains("procedure 'get-customer'"), e.getMessage());
        assertTrue(e.getMessage().contains("SQLState"), e.getMessage());
        assertFalse(e.getMessage().contains("custommers"), e.getMessage());
        assertNull(adapter.dataSource(), "a failed start must not leave a pool behind");
    }

    @Test
    void startShouldFailOnAStoredProcedureTheCatalogDoesNotList() throws Exception {
        adapter = new SqlClientAdapter(null, spec(url, "verify", VALID.replace(
                "storedProcedure: get_name", "storedProcedure: get_nme")));

        IllegalStateException e = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(e.getMessage().contains("procedure 'get-name'"), e.getMessage());
    }

    @Test
    void startShouldFindAStoredProcedureQualifiedByItsSchema() throws Exception {
        adapter = new SqlClientAdapter(null, spec(url, "verify", VALID.replace(
                "storedProcedure: get_name", "storedProcedure: public.get_name")));

        assertDoesNotThrow(adapter::start);
    }

    @Test
    void startShouldFailWhenTheDatabaseIsUnreachable() throws Exception {
        adapter = new SqlClientAdapter(null, spec("jdbc:hsqldb:hsql://127.0.0.1:1/none",
                "verify", VALID));

        IllegalStateException e = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(e.getMessage().contains("cannot connect"), e.getMessage());
    }

    @Test
    void startShouldNotConnectWhenDiscoveryIsOff() throws Exception {
        adapter = new SqlClientAdapter(null, spec("jdbc:hsqldb:hsql://127.0.0.1:1/none", "off",
                VALID));

        assertDoesNotThrow(adapter::start);
    }

    @Test
    void discoveryOffShouldLeaveTheErrorToTheFirstCall() throws Exception {
        adapter = new SqlClientAdapter(null, spec(url, "off", VALID.replace(
                "from customers where id", "from custommers where id")));
        adapter.start();

        assertEquals(502, adapter.prepare("get-customer", Map.of("id", "c1")).invoke().status());
    }

    @Test
    void discoveryShouldReadABareYamlOffAsOff() throws Exception {
        SqlClientSpec spec = spec(url, "false", VALID);

        assertEquals("off", spec.getDiscovery());
        assertFalse(spec.isDiscoveryVerify());
    }

    @Test
    void discoveryShouldDefaultToVerify() throws Exception {
        SqlClientSpec spec = spec(url, "verify", VALID);
        spec.setDiscovery(null);

        assertTrue(spec.isDiscoveryVerify());
    }
}
