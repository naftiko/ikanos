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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A capability that consumes a SQLite database with {@code consumes: type: sql} and re-exposes
 * its procedures over REST, end to end: YAML, adapter registration, the call path, output
 * mappings and the HTTP answer. SQLite runs in-process on a temporary file.
 */
class SqlClientAdapterIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();
    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path dir;

    private Capability capability;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("catalog.db").toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url);
                Statement ddl = connection.createStatement()) {
            ddl.execute("create table products (sku text primary key, name text,"
                    + " quantity integer, price numeric)");
            ddl.execute("insert into products values ('SKU-1', 'Anvil', 12, 99.5)");
            ddl.execute("insert into products values ('SKU-2', 'Rocket', 0, 1500)");
        }
        port = freePort();
        capability = new Capability(YAML.readValue(yaml(url, port), IkanosSpec.class));
        capability.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (capability != null) {
            capability.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String yaml(String url, int port) {
        return """
                ikanos: "%s"
                info:
                  display: SQL client integration
                  description: Re-exposes a SQLite catalog over REST.
                capability:
                  consumes:
                    - type: sql
                      namespace: catalog
                      description: Product catalog on SQLite.
                      connection:
                        url: "%s"
                      procedures:
                        list-products:
                          description: All products, by SKU.
                          statement: select sku, name, quantity from products order by sku
                        get-stock:
                          description: Stock of one SKU.
                          statement: select name, quantity from products where sku = :sku
                          inputParameters:
                            sku: { type: string }
                  exposes:
                    - type: rest
                      address: localhost
                      port: %d
                      namespace: catalog-rest
                      resources:
                        products:
                          path: /products
                          description: Products.
                          operations:
                            list-products:
                              method: GET
                              call: catalog.list-products
                              outputParameters:
                                - type: array
                                  mapping: $.rows
                                  items:
                                    type: object
                                    properties:
                                      sku: { type: string, mapping: $.sku }
                                      quantity: { type: number, mapping: $.quantity }
                        stock:
                          path: /stock/{sku}
                          description: Stock of one SKU.
                          operations:
                            get-stock:
                              method: GET
                              call: catalog.get-stock
                              inputParameters:
                                sku: { in: path, type: string, description: SKU }
                              with:
                                sku: "{{sku}}"
                """.formatted(IKANOS, url, port);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void restCallShouldMapEveryRowOfTheDocument() throws Exception {
        HttpResponse<String> response = get("/products");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(2, body.size(), response.body());
        assertEquals("SKU-2", body.get(1).get("sku").asString());
        assertEquals(0, body.get(1).get("quantity").asInt());
    }

    @Test
    void restCallShouldBindThePathParameterAsABindVariable() throws Exception {
        HttpResponse<String> response = get("/stock/SKU-1");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(1, body.get("rowCount").asInt(), response.body());
        assertEquals("Anvil", body.get("rows").get(0).get("name").asString());
        assertEquals(12, body.get("rows").get(0).get("quantity").asInt());
    }

    @Test
    void restCallShouldTreatAnInjectionAttemptAsAPlainValue() throws Exception {
        HttpResponse<String> response = get("/stock/x'%20or%20'1'='1");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, JSON.readTree(response.body()).get("rowCount").asInt());
    }

    @Test
    void capabilityShouldRegisterTheSqlAdapterForTypeSql() {
        assertInstanceOf(SqlClientAdapter.class, capability.getClientAdapters().get(0));
    }

    @Test
    void capabilityShouldRejectACallToAnUndeclaredProcedureAtLoadTime() {
        String broken = yaml("jdbc:sqlite:unused.db", 0)
                .replace("call: catalog.list-products", "call: catalog.drop-products");

        Exception e = assertThrows(Exception.class,
                () -> new Capability(YAML.readValue(broken, IkanosSpec.class)));

        assertTrue(String.valueOf(e.getMessage()).contains("drop-products"), e.getMessage());
    }
}
