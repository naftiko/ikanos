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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * An MCP tool advertised as read-only, by its own hints or the exposes block's, that reaches a
 * SQL write procedure is refused when the capability loads.
 */
class SqlWriteExposureTest {

    private static final ObjectMapper YAML = IkanosMappers.lenientYaml();

    private static Capability load(String hints, String tool) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: sql
                      namespace: erp
                      description: ERP database.
                      connection:
                        url: "jdbc:hsqldb:mem:write-exposure"
                        username: SA
                        password: ""
                      procedures:
                        get-order:
                          description: Reads one order.
                          statement: select id from orders where id = {{id}}
                          inputParameters:
                            id: { type: string }
                        mark-shipped:
                          description: Marks one order as shipped.
                          readOnly: false
                          statement: update orders set status = 'SHIPPED' where id = {{id}}
                          inputParameters:
                            id: { type: string }
                  aggregates:
                    - namespace: orders
                      flows:
                        settle:
                          description: Settles one order.
                          inputParameters:
                            id: { type: string, description: Order id }
                          steps:
                            - name: ship
                              type: call
                              what: erp.mark-shipped
                              with:
                                id: orders.id
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: erp-mcp
                      description: Order tools
                %s
                      tools:
                %s
                """.formatted(VersionHelper.getSchemaVersion(), hints.indent(6).stripTrailing(),
                tool.indent(8).stripTrailing());
        return new Capability(YAML.readValue(yaml, IkanosSpec.class));
    }

    @Test
    void loadShouldRefuseAWriteToolInheritingReadOnlyFromTheExposesBlock() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("hints: { readOnly: true }", """
                        ship:
                          description: Ships an order.
                          call: erp.mark-shipped
                        """));

        assertTrue(e.getMessage().contains("erp.mark-shipped")
                && e.getMessage().contains("'ship'"), e.getMessage());
    }

    @Test
    void loadShouldRefuseAToolDeclaringReadOnlyThatReachesAWriteThroughAFlow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("", """
                        settle:
                          description: Settles an order.
                          ref: orders.settle
                          hints: { readOnly: true }
                        """));

        assertTrue(e.getMessage().contains("erp.mark-shipped"), e.getMessage());
    }

    @Test
    void loadShouldAcceptAWriteToolThatOverridesTheInheritedReadOnly() {
        assertDoesNotThrow(() -> load("hints: { readOnly: true }", """
                ship:
                  description: Ships an order.
                  call: erp.mark-shipped
                  hints: { readOnly: false }
                get-order:
                  description: Reads an order.
                  call: erp.get-order
                """));
    }
}
