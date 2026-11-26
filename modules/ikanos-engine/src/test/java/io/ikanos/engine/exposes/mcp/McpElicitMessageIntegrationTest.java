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
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * An approval whose {@code elicit} message names what it approves, read by a {@code call} step
 * and a {@code lookup} step that run before it (issue 1122). The consumed operations are the
 * test {@code fake} adapter's canned responses. Under {@code none} the steps run again on each
 * round; under {@code memory} the call output is restored from the journal.
 */
class McpElicitMessageIntegrationTest {

    private static final String SEALING_KEY = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String CALL = "{\"name\":\"delete-post\",\"arguments\":{\"postId\":\"42\"}}";
    private static final String KEY = "steps/approve";
    private static final String EXPECTED = "Permanently delete 'Hello World' by Grace (#42)?";

    private final ObjectMapper yamlMapper = IkanosMappers.yaml();
    private final ObjectMapper jsonMapper = IkanosMappers.json();

    private static String capability(String mode) {
        return """
                ikanos: "%s"
                info:
                  display: "Post deletion"
                  description: "Templated elicit message integration test"
                capability:
                  consumes:
                    - type: fake
                      namespace: blog
                      operations:
                        get-post:
                          outputParameters:
                            - name: title
                              type: string
                              mapping: $.title
                            - name: author
                              type: string
                              mapping: $.author
                        list-authors:
                          outputParameters: []
                      responses:
                        get-post: '{"id":"{{postId}}","title":"Hello World","author":"a-2"}'
                        list-authors: '[{"id":"a-1","name":"Ada"},{"id":"a-2","name":"Grace"}]'
                  aggregates:
                  - namespace: posts
                    flows:
                      delete-post:
                        description: Delete a post once the user approves.
                        inputParameters:
                          postId:
                            type: string
                            description: Post to delete
                        steps:
                          - name: post
                            type: call
                            what: blog.get-post
                            with:
                              postId: posts.postId
                          - name: authors
                            type: call
                            what: blog.list-authors
                          - name: author
                            type: lookup
                            index: authors
                            match: id
                            lookupValue: $.post.author
                            outputParameters:
                              - name
                          - name: approve
                            type: elicit
                            message: "Permanently delete '{{post.title}}' by {{author.name}} (#{{postId}})?"
                            fields:
                              confirm:
                                type: string
                                enum: [DELETE]
                                description: Choose DELETE to confirm
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 0
                    namespace: blog-safe
                    description: Blog operations that ask before deleting.
                    tools:
                      delete-post:
                        description: Permanently delete a post, once approved.
                        ref: posts.delete-post
                  - type: control
                    address: localhost
                    port: 0
                    durability:
                      mode: %s
                      sealingKey: "%s"
                """.formatted(VersionHelper.getSchemaVersion(), mode, SEALING_KEY);
    }

    private McpServerAdapter load(String mode) throws Exception {
        IkanosSpec spec = yamlMapper.readValue(capability(mode), IkanosSpec.class);
        for (ServerAdapter adapter : new Capability(spec).getServerAdapters()) {
            if (adapter instanceof McpServerAdapter mcp) {
                return mcp;
            }
        }
        throw new IllegalStateException("No MCP adapter");
    }

    private ObjectNode call(McpServerAdapter adapter, String paramsJson) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = (ObjectNode) jsonMapper.readTree(paramsJson);
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", ProtocolDispatcher.MCP_PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities").putObject("elicitation");
        request.set("params", params);
        return new ProtocolDispatcher(adapter).dispatch(request).responseBody();
    }

    private static String message(JsonNode result) {
        return result.path("inputRequests").path(KEY).path("params").path("message").asString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void elicitMessageShouldNameWhatTheEarlierCallAndLookupRead(String mode) throws Exception {
        JsonNode first = call(load(mode), CALL).path("result");

        assertEquals("input_required", first.path("resultType").asString(), first.toString());
        assertEquals(EXPECTED, message(first));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "memory"})
    void elicitMessageShouldBeTheSameWhenARetryWithoutAnAnswerAsksAgain(String mode)
            throws Exception {
        McpServerAdapter adapter = load(mode);
        JsonNode first = call(adapter, CALL).path("result");
        ObjectNode retry = (ObjectNode) jsonMapper.readTree(CALL);
        retry.put("requestState", first.path("requestState").asString());

        JsonNode second = call(adapter, retry.toString()).path("result");

        assertEquals("input_required", second.path("resultType").asString(), second.toString());
        assertEquals(EXPECTED, message(second));
    }
}
