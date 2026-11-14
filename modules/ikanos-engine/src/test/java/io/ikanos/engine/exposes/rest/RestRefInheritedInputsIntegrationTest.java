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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * A REST operation that uses {@code ref} and declares no inputs, nor its resource, accepts its
 * aggregate flow's inputs, as an MCP tool does (#1039). Requests go through the adapter's router,
 * so path variables are extracted the way they are when the adapter serves them.
 */
public class RestRefInheritedInputsIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    private RestServerAdapter adapter;

    @BeforeEach
    void loadCapability() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(
                new File("src/test/resources/aggregates/aggregate-rest-ref-inherited-inputs.yaml"),
                IkanosSpec.class);
        adapter = (RestServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    @Test
    void handleShouldPassThePathVariableToTheFlowWhenTheOperationDeclaresNoInputs()
            throws Exception {
        JsonNode body = send(Method.GET, "/ships/IMO-9321483", null, null);

        assertEquals("IMO-9321483", body.path("imo").asString(),
                "the design's §6.2 example: the flow must receive imo from the path");
    }

    @Test
    void handleShouldPassAMustachePathVariableToTheFlow() throws Exception {
        JsonNode body = send(Method.GET, "/vessels/IMO-7654321", null, null);

        assertEquals("IMO-7654321", body.path("imo").asString());
    }

    @Test
    void handleShouldPassAQueryParameterToTheFlowForGet() throws Exception {
        JsonNode body = send(Method.GET, "/ships?flag=NO", null, null);

        assertEquals("NO", body.path("flag").asString());
    }

    @Test
    void handleShouldPassJsonBodyMembersToTheFlowForPost() throws Exception {
        JsonNode body = send(Method.POST, "/ships",
                "{\"imo\":\"IMO-1111111\",\"name\":\"Aurora\"}", null);

        assertEquals("IMO-1111111", body.path("imo").asString());
        assertEquals("Aurora", body.path("name").asString());
    }

    @Test
    void handleShouldUseTheOperationsOwnInputsInsteadOfTheFlows() throws Exception {
        JsonNode body = send(Method.GET, "/own?imo=IMO-QUERY", null, "IMO-HEADER");

        assertEquals("IMO-HEADER", body.path("imo").asString(),
                "own inputs replace the flow's: imo must not be read from the query string");
    }

    private JsonNode send(Method method, String path, String jsonBody, String imoHeader)
            throws Exception {
        Request request = new Request(method, new Reference("http://localhost:9212" + path));
        request.getResourceRef().setBaseRef("http://localhost:9212");
        if (jsonBody != null) {
            request.setEntity(jsonBody, MediaType.APPLICATION_JSON);
        }
        if (imoHeader != null) {
            request.getHeaders().add("imo", imoHeader);
        }
        Response response = new Response(request);

        adapter.getRouter().handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus(), path);
        String text = response.getEntity().getText();
        assertTrue(text != null && !text.isBlank(), path + ": empty body");
        return JSON.readTree(text);
    }
}
