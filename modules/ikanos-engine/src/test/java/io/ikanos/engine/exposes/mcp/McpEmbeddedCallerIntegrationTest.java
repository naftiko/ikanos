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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.modelcontextprotocol.spec.McpSchema;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.CallerClaims;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * An application that embeds the engine and calls {@code handleToolCall} directly, with no MCP
 * server started, passes the claims it validated itself to {@code authorization} (#999) and to
 * {@code {{auth.claims...}}} templates (#998) through {@link AccessControl#check(AuthorizationSpec,
 * Map)} and {@link CallerClaims#of(Map)} (#1126).
 */
class McpEmbeddedCallerIntegrationTest {

    private static final Map<String, Object> ADMIN =
            Map.of("sub", "alice", "scope", "fleet.read fleet.admin");

    private static final Map<String, Object> READER = Map.of("sub", "bob", "scope", "fleet.read");

    @Test
    void handleToolCallShouldSendTheEmbeddedCallersSubjectToTheConsumedApi() throws Exception {
        try (RecordingUpstream upstream = new RecordingUpstream()) {
            McpServerAdapter adapter = load(upstream);

            McpSchema.CallToolResult result = CallerClaims.runAs(CallerClaims.of(ADMIN),
                    () -> call(adapter, "order-status"));

            assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result.content()));
            assertEquals("alice", upstream.requests().get(0).get("X-User-Id"));
            assertFalse(adapter.getServer().isStarted(), "no server is started to embed the engine");
        }
    }

    @Test
    void checkShouldAcceptAnEmbeddedCallerHoldingTheToolsScope() throws Exception {
        try (RecordingUpstream upstream = new RecordingUpstream()) {
            assertNull(AccessControl.check(requirement(load(upstream), "order-status"), ADMIN));
        }
    }

    @Test
    void checkShouldRefuseAnEmbeddedCallerWithoutTheToolsScope() throws Exception {
        try (RecordingUpstream upstream = new RecordingUpstream()) {
            AccessControl.Denial denial =
                    AccessControl.check(requirement(load(upstream), "order-status"), READER);

            assertNotNull(denial);
            assertEquals(java.util.List.of("fleet.admin"), denial.scopes());
        }
    }

    private static AuthorizationSpec requirement(McpServerAdapter adapter, String tool) {
        return AccessControl.effective(adapter.getMcpServerSpec(),
                adapter.getMcpServerSpec().getTools().get(tool));
    }

    private static McpSchema.CallToolResult call(McpServerAdapter adapter, String tool) {
        try {
            return adapter.getToolHandler().handleToolCall(tool, Map.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static McpServerAdapter load(RecordingUpstream upstream) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Embedded caller"
                  description: "Embedded caller integration test"
                capability:
                  exposes:
                    - type: "mcp"
                      address: "127.0.0.1"
                      port: 0
                      namespace: "embedded"
                      description: "OAuth2 protected MCP adapter, embedded"
                      authentication:
                        type: "oauth2"
                        authorizationServerUri: "http://127.0.0.1:1"
                        resource: "http://127.0.0.1/mcp"
                        scopes: ["fleet.read", "fleet.admin"]
                      tools:
                        order-status:
                          description: "Status of order 42, for fleet admins"
                          authorization:
                            scopes: ["fleet.admin"]
                          call: "shop.get-order"
                          outputParameters:
                            - type: "string"
                              mapping: "$.status"
                  consumes:
                    - type: "http"
                      namespace: "shop"
                      baseUri: "%s"
                      inputParameters:
                        X-User-Id:
                          in: "header"
                          value: "{{auth.claims.sub}}"
                      resources:
                        order:
                          path: "/orders/42"
                          operations:
                            get-order:
                              method: "GET"
                """.formatted(VersionHelper.getSchemaVersion(), upstream.baseUri());
        Capability capability =
                new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }
}
