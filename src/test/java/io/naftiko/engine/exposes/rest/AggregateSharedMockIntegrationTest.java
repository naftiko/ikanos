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
package io.naftiko.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.mcp.McpServerAdapter;
import io.naftiko.engine.exposes.mcp.ProtocolDispatcher;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerSpec;

/**
 * Integration test proving that a single aggregate function mock can be reused unchanged by
 * both MCP and REST adapters.
 */
public class AggregateSharedMockIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aggregateMockShouldReturnSamePayloadForMcpAndRest() throws Exception {
        Capability capability = loadCapability("src/test/resources/aggregates/aggregate-shared-mock.yaml");

        McpServerAdapter mcpAdapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(mcpAdapter);

        JsonNode mcpResponse = dispatcher.dispatch(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"hello\",\"arguments\":{\"name\":\"Nina\"}}}"));

        assertFalse(mcpResponse.path("result").path("isError").asBoolean(),
                "MCP tools/call should not fail for aggregate mock ref");

        JsonNode mcpPayload = JSON.readTree(mcpResponse.path("result").path("content")
                .get(0).path("text").asText());

        RestServerAdapter restAdapter = (RestServerAdapter) capability.getServerAdapters().get(1);
        RestServerSpec restSpec = (RestServerSpec) restAdapter.getSpec();
        ResourceRestlet restlet = new ResourceRestlet(capability, restSpec,
                restSpec.getResources().get(0));
        RestServerOperationSpec restOperation = restSpec.getResources().get(0).getOperations().get(0);

        assertEquals(2, restOperation.getOutputParameters().size(),
                "REST operation should inherit two aggregate output parameters");

        assertTrue(restlet.canBuildMockResponse(restOperation),
                "REST operation should inherit aggregate mock output parameters");

        Request request = new Request(Method.GET, "http://localhost/hello?name=Nina");
        Response response = new Response(request);
        restlet.sendMockResponse(restOperation, response, Map.of("name", "Nina"));

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        JsonNode restPayload = JSON.readTree(response.getEntity().getText());

        assertEquals("Hello, Nina!", mcpPayload.path("message").asText());
        assertEquals("aggregate-mock", mcpPayload.path("source").asText());
        assertEquals(mcpPayload, restPayload,
                "MCP and REST should share the same aggregate mock output");
    }

    private Capability loadCapability(String path) throws Exception {
        File file = new File(path);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);
        return new Capability(spec);
    }
}
