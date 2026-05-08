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
package io.ikanos.engine.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.aggregates.AggregateFunction;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

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
        RestServerOperationSpec restOperation = restSpec.getResources().get(0).getOperations().get(0);

        // Output parameters are no longer copied — they live on the aggregate function
        AggregateFunction fn = capability.lookupFunction(restOperation.getRef());
        assertNotNull(fn, "Aggregate function should be resolvable from ref");
        assertEquals(2, fn.getOutputParameters().size(),
                "Aggregate function should have two output parameters");

        // Exercise REST path through the normal flow (delegating to aggregate function)
        ResourceRestlet restlet = new ResourceRestlet(capability, restSpec,
                restSpec.getResources().get(0));
        Request request = new Request(Method.GET, new Reference("http://localhost/hello?name=Nina"));
        Response response = new Response(request);
        restlet.handle(request, response);

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
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);
        return new Capability(spec);
    }
}
