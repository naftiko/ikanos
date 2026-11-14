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
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * A REST input reaches the operation as its declared type, the way the OpenAPI export describes
 * it, and a path placeholder may have a hyphen in its name. Requests go through the adapter's
 * router, so path variables are extracted the way they are when the adapter serves them.
 */
public class RestInputTypesIntegrationTest {

    private static final ObjectMapper JSON = IkanosMappers.lenientJson();

    private RestServerAdapter adapter;

    @BeforeEach
    void loadCapability() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(
                new File("src/test/resources/rest/rest-input-types.yaml"), IkanosSpec.class);
        adapter = (RestServerAdapter) new Capability(spec).getServerAdapters().get(0);
    }

    @Test
    void handleShouldRouteAPathPlaceholderWithAHyphenatedName() throws Exception {
        JsonNode body = get("/crew/IMO-1234567");

        assertEquals("IMO-1234567", body.path("ship").asString());
    }

    @Test
    void handleShouldPassEveryValueOfARepeatedQueryParameterForAnArrayInput()
            throws Exception {
        JsonNode body = get("/ships?ids=IMO-1&ids=IMO-2");

        // The mock output renders the list as JSON: both values reached the operation
        assertEquals("[\"IMO-1\",\"IMO-2\"]", body.path("ids").asString());
    }

    @Test
    void handleShouldReadAnObjectQueryInputAsJson() throws Exception {
        JsonNode body = get("/find?filter="
                + URLEncoder.encode("{\"flag\":\"NO\"}", StandardCharsets.UTF_8));

        assertEquals("NO", body.path("flag").asString());
    }

    private JsonNode get(String path) throws Exception {
        Request request = new Request(Method.GET, new Reference("http://localhost:9213" + path));
        request.getResourceRef().setBaseRef("http://localhost:9213");
        Response response = new Response(request);

        adapter.getRouter().handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus(), path);
        return JSON.readTree(response.getEntity().getText());
    }
}
