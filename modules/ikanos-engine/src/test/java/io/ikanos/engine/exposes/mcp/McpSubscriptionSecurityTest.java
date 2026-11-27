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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import com.nimbusds.jwt.JWTClaimsSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.IntNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.OAuth2AuthenticationRestlet;
import io.ikanos.engine.exposes.mcp.handler.SubscriptionsListenHandler;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * What a {@code subscriptions/listen} stream may carry for the caller that opened it: only the
 * tasks whose tool's {@code authorization} its token meets, until the token expires, and at most
 * {@link McpServerAdapter#MAX_SUBSCRIPTIONS_PER_OWNER} open streams per caller.
 */
class McpSubscriptionSecurityTest {

    private static final ObjectMapper JSON = IkanosMappers.json();
    private static final String NAMESPACE = "records-mcp";

    private McpServerAdapter adapter;
    private ProtocolDispatcher dispatcher;

    @BeforeEach
    void load() throws Exception {
        String yaml = "ikanos: " + VersionHelper.getSchemaVersion() + "\n" + """
                info:
                  display: Subscription security
                  description: Tasks of tools with and without their own authorization
                capability:
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9104
                    namespace: records-mcp
                    authentication:
                      type: oauth2
                      authorizationServerUri: https://idp.example.org
                      resource: https://app.example.org/mcp
                      scopes: [records.read, records.write]
                    authorization:
                      scopes: [records.read]
                    tools:
                      read-record:
                        description: Read a record.
                        outputParameters:
                        - type: string
                          value: ok
                      update-record:
                        description: Update a record.
                        authorization:
                          scopes: [records.write]
                        outputParameters:
                        - type: string
                          value: ok
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: memory
                """;
        Capability capability = new Capability(
                IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
        adapter = (McpServerAdapter) capability.getServerAdapters().stream()
                .filter(McpServerAdapter.class::isInstance).findFirst().orElseThrow();
        dispatcher = new ProtocolDispatcher(adapter);
    }

    @Test
    void secureShouldLeaveOutTheTasksOfToolsTheTokenMayNotCall() {
        Request alice = request("alice", "records.read", null);
        String owner = ownerOf(alice);
        String readTask = task("read-record", owner);
        String updateTask = task("update-record", owner);
        McpSubscription subscription =
                adapter.subscribe(IntNode.valueOf(1), owner, List.of(readTask, updateTask));

        McpServerResource.secure(dispatcher, subscription, alice);

        assertEquals(Set.of(readTask), subscription.agreed(),
                "update-record needs records.write, which the token lacks, as tasks/get does");
        subscription.discard();
    }

    @Test
    void secureShouldKeepEveryTaskOfToolsTheTokenMayCall() {
        Request alice = request("alice", "records.read records.write", null);
        String owner = ownerOf(alice);
        String readTask = task("read-record", owner);
        String updateTask = task("update-record", owner);
        McpSubscription subscription =
                adapter.subscribe(IntNode.valueOf(1), owner, List.of(readTask, updateTask));

        McpServerResource.secure(dispatcher, subscription, alice);

        assertEquals(Set.of(readTask, updateTask), subscription.agreed());
        subscription.discard();
    }

    @Test
    void secureShouldEndTheSubscriptionWhenTheTokenExpires() {
        Date expiry = new Date(System.currentTimeMillis() + 60_000);
        Request alice = request("alice", "records.read", expiry);
        McpSubscription subscription =
                adapter.subscribe(IntNode.valueOf(1), ownerOf(alice), List.of());

        McpServerResource.secure(dispatcher, subscription, alice);

        assertEquals(expiry.toInstant(), subscription.expiresAt());
        subscription.discard();
    }

    @Test
    void secureShouldNotEndTheSubscriptionOfATokenWithoutExpiry() {
        Request alice = request("alice", "records.read", null);
        McpSubscription subscription =
                adapter.subscribe(IntNode.valueOf(1), ownerOf(alice), List.of());

        McpServerResource.secure(dispatcher, subscription, alice);

        assertNull(subscription.expiresAt());
        subscription.discard();
    }

    @Test
    void aCallerShouldKeepAtMostTheMaximumOfSubscriptionsOpen() throws Exception {
        List<McpSubscription> open = new ArrayList<>();
        for (int i = 0; i < McpServerAdapter.MAX_SUBSCRIPTIONS_PER_OWNER; i++) {
            open.add(adapter.subscribe(IntNode.valueOf(i), "alice", List.of()));
        }

        JsonNode refused = listen("alice-past-the-limit");
        assertEquals(-32600, refused.path("error").path("code").asInt(),
                "A request past the limit is refused: " + refused);

        // Another caller is not held back by alice's subscriptions.
        McpSubscription other = adapter.subscribe(IntNode.valueOf(99), "bob", List.of());
        assertNotNull(other);
        other.discard();
        open.forEach(McpSubscription::discard);
        assertEquals(0, adapter.subscriptionCount());
    }

    /**
     * A subscriptions/listen handled as alice, who already holds the maximum. The handler is
     * called directly: this fixture's tools run inline, so the server registers no task methods.
     */
    private JsonNode listen(String label) throws Exception {
        JsonNode request = JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":\"" + label + "\","
                + "\"method\":\"subscriptions/listen\",\"params\":{\"notifications\":{}}}");
        SubscriptionsListenHandler handler =
                new SubscriptionsListenHandler(adapter, List.of(), List.of(), List.of());
        HandlerResult result = McpCallContext.as("alice", () -> handler.handle(request));
        return ((HandlerFailureResult) result).body();
    }

    private String task(String tool, String owner) {
        return adapter.getTaskService().create("mcp:" + NAMESPACE + "/" + tool, owner,
                JSON.createObjectNode(), JSON::createObjectNode).path("taskId").asString();
    }

    private static String ownerOf(Request request) {
        return AgentIdentityResolver.ownerOf(NAMESPACE, request, Optional.empty());
    }

    private static Request request(String subject, String scope, Date expiry) {
        Request request = new Request(Method.POST, "http://localhost/mcp");
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer("https://idp.example.org")
                .subject(subject).claim("scope", scope);
        if (expiry != null) {
            claims.expirationTime(expiry);
        }
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                claims.build());
        return request;
    }
}
