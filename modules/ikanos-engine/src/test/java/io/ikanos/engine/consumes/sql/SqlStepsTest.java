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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * SQL results used through {@code steps:}: the rows reach the aggregate mappings as the adapter
 * built them, so a decimal keeps its precision.
 */
class SqlStepsTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private String url;
    private Connection keepAlive;
    private Capability capability;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:hsqldb:mem:" + UUID.randomUUID();
        keepAlive = DriverManager.getConnection(url, "SA", "");
        try (Statement ddl = keepAlive.createStatement()) {
            ddl.execute("create table ledger (id varchar(10) primary key, total decimal(30,2))");
            ddl.execute("insert into ledger values ('l1', 12345678901234567890.12)");
        }
        capability = new Capability(YAML.readValue(yaml(), IkanosSpec.class));
        capability.getClientAdapters().get(0).start();
    }

    @AfterEach
    void tearDown() throws Exception {
        capability.getClientAdapters().get(0).stop();
        try (Statement shutdown = keepAlive.createStatement()) {
            shutdown.execute("shutdown");
        }
        keepAlive.close();
    }

    private String yaml() {
        return """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: sql
                      namespace: erp
                      description: Ledger database.
                      connection:
                        url: "%s"
                        username: SA
                        password: ""
                      procedures:
                        get-total:
                          description: The total of one ledger.
                          statement: select total from ledger where id = :id
                          inputParameters:
                            id: { type: string }
                  aggregates:
                    - namespace: books
                      flows:
                        ledger-total:
                          description: The total of one ledger, through a step.
                          inputParameters:
                            ledger-id:
                              type: string
                              description: Ledger id
                          steps:
                            ledger:
                              type: call
                              call: erp.get-total
                              with:
                                id: books.ledger-id
                          mappings:
                            - target: total
                              value: $.ledger.rows[0].TOTAL
                          outputParameters:
                            total:
                              type: number
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: books-mcp
                      description: Ledger tools
                      tools:
                        ledger-total:
                          description: The total of one ledger
                          ref: books.ledger-total
                """.formatted(VersionHelper.getSchemaVersion(), url);
    }

    @Test
    void aggregateFlowShouldKeepTheDecimalPrecisionOfASqlStepResult() throws Exception {
        McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);

        McpSchema.CallToolResult result = mcp.getToolHandler()
                .handleToolCall("ledger-total", Map.of("ledger-id", "l1"));

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(Boolean.TRUE.equals(result.isError()), text);
        assertTrue(text.contains("12345678901234567890.12"), text);
    }

    @Test
    void stepParametersShouldKeepTheDecimalOfASqlStepResult() throws Exception {
        Map<String, Object> parameters = new java.util.HashMap<>();
        tools.jackson.databind.JsonNode output = tools.jackson.databind.node.JsonNodeFactory
                .instance.objectNode().put("total", new java.math.BigDecimal(
                        "12345678901234567890.12"));

        new io.ikanos.engine.util.OperationStepExecutor(capability)
                .addStepOutputToParameters(parameters, "ledger", output);

        Object total = ((Map<?, ?>) parameters.get("ledger")).get("total");
        org.junit.jupiter.api.Assertions.assertEquals(
                new java.math.BigDecimal("12345678901234567890.12"), total);
    }
}
