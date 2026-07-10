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
package io.ikanos.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.exposes.control.ScriptingManagementSpec;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.representation.StringRepresentation;
import org.restlet.routing.TemplateRoute;

import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Integration tests for scripting governance on the Control Port.
 * Tests YAML deserialization, ScriptingResource GET/PUT, and router wiring.
 */
public class ScriptingIntegrationTest {

    private Capability capability;
    private ControlServerAdapter adapter;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/control/control-scripting-capability.yaml";
        File file = new File(resourcePath);
        assertTrue(file.exists(), "Scripting capability test file should exist");

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        capability = new Capability(spec);
        adapter = (ControlServerAdapter) capability.getServerAdapters().get(0);
    }

    // ── YAML deserialization ─────────────────────────────────────

    @Test
    public void scriptingSpecShouldBeDeserializedFromYaml() {
        ControlServerSpec spec = adapter.getControlServerSpec();
        ScriptingManagementSpec scripting = spec.getManagement().getScripting();

        assertNotNull(scripting);
        assertTrue(scripting.isEnabled());
        assertEquals("file:///app/scripts", scripting.getDefaultLocation());
        assertEquals("javascript", scripting.getDefaultLanguage());
        assertEquals(3000, scripting.getTimeout());
        assertEquals(50000, scripting.getStatementLimit());
        assertEquals(2, scripting.getAllowedLanguages().size());
    }

    // ── Router wiring ────────────────────────────────────────────

    @Test
    public void routerShouldAttachScriptingEndpoint() {
        Set<String> patterns = adapter.getRouter().getRoutes().stream()
                .filter(TemplateRoute.class::isInstance)
                .map(route -> ((TemplateRoute) route).getTemplate().getPattern())
                .collect(Collectors.toSet());

        assertTrue(patterns.contains("/scripting"),
                "/scripting should be attached when scripting is configured");
    }

    @Test
    public void routerShouldNotAttachScriptingWhenNotConfigured() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(
                new File("src/test/resources/control/control-capability.yaml"),
                IkanosSpec.class);
        Capability capWithoutScripting = new Capability(spec);
        ControlServerAdapter adapterNoScripting =
                (ControlServerAdapter) capWithoutScripting.getServerAdapters().get(0);

        Set<String> patterns = adapterNoScripting.getRouter().getRoutes().stream()
                .filter(TemplateRoute.class::isInstance)
                .map(route -> ((TemplateRoute) route).getTemplate().getPattern())
                .collect(Collectors.toSet());

        assertFalse(patterns.contains("/scripting"),
                "/scripting should not be attached when scripting is not configured");
    }

    // ── GET /scripting ───────────────────────────────────────────

    @Test
    public void getScriptingShouldReturnConfigAndStats() throws Exception {
        Request request = new Request(Method.GET, "/scripting");
        Response response = new Response(request);
        adapter.getRouter().handle(request, response);

        assertEquals(Status.SUCCESS_OK, response.getStatus());
        String body = response.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);

        assertTrue(json.get("enabled").asBoolean());
        assertEquals("file:///app/scripts", json.get("defaultLocation").asText());
        assertEquals("javascript", json.get("defaultLanguage").asText());
        assertEquals(3000, json.get("timeout").asInt());
        assertEquals(50000, json.get("statementLimit").asLong());
        assertTrue(json.has("allowedLanguages"));
        assertTrue(json.has("stats"));

        JsonNode stats = json.get("stats");
        assertEquals(0, stats.get("totalExecutions").asLong());
        assertEquals(0, stats.get("totalErrors").asLong());
    }

    // ── PUT /scripting ───────────────────────────────────────────

    @Test
    public void putScriptingShouldUpdateEnabledFlag() throws Exception {
        // Disable scripting via PUT
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation("{\"enabled\": false}", MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        assertEquals(Status.SUCCESS_OK, putResponse.getStatus());

        // Verify the response shows updated config
        String body = putResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);
        assertFalse(json.get("enabled").asBoolean());
    }

    @Test
    public void putScriptingShouldUpdateTimeout() throws Exception {
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation("{\"timeout\": 10000}", MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        assertEquals(Status.SUCCESS_OK, putResponse.getStatus());

        String body = putResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);
        assertEquals(10000, json.get("timeout").asInt());
    }

    @Test
    public void putScriptingShouldUpdateStatementLimit() throws Exception {
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation("{\"statementLimit\": 200000}",
                        MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        assertEquals(Status.SUCCESS_OK, putResponse.getStatus());

        String body = putResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);
        assertEquals(200000, json.get("statementLimit").asLong());
    }

    @Test
    public void putScriptingShouldUpdateDefaultLocation() throws Exception {
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation("{\"defaultLocation\": \"file:///new/path\"}",
                        MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        assertEquals(Status.SUCCESS_OK, putResponse.getStatus());

        String body = putResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);
        assertEquals("file:///new/path", json.get("defaultLocation").asText());
    }

    @Test
    public void putScriptingShouldUpdateDefaultLanguage() throws Exception {
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation("{\"defaultLanguage\": \"python\"}",
                        MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        assertEquals(Status.SUCCESS_OK, putResponse.getStatus());

        String body = putResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);
        assertEquals("python", json.get("defaultLanguage").asText());
    }

    @Test
    public void putScriptingShouldUpdateAllowedLanguages() throws Exception {
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation(
                        "{\"allowedLanguages\": [\"javascript\", \"python\"]}",
                        MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        assertEquals(Status.SUCCESS_OK, putResponse.getStatus());

        String body = putResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);
        assertTrue(json.has("allowedLanguages"));
        assertEquals(2, json.get("allowedLanguages").size());
        assertEquals("javascript", json.get("allowedLanguages").get(0).asText());
        assertEquals("python", json.get("allowedLanguages").get(1).asText());
    }

    // ── Runtime effect ───────────────────────────────────────────

    @Test
    public void putScriptingShouldTakeEffectOnSubsequentGet() throws Exception {
        // Update multiple fields
        Request putRequest = new Request(Method.PUT, "/scripting");
        putRequest.setEntity(
                new StringRepresentation(
                        "{\"enabled\": false, \"timeout\": 7000, \"statementLimit\": 75000}",
                        MediaType.APPLICATION_JSON));
        Response putResponse = new Response(putRequest);
        adapter.getRouter().handle(putRequest, putResponse);

        // GET should reflect the updates
        Request getRequest = new Request(Method.GET, "/scripting");
        Response getResponse = new Response(getRequest);
        adapter.getRouter().handle(getRequest, getResponse);

        String body = getResponse.getEntity().getText();
        JsonNode json = JSON_MAPPER.readTree(body);

        assertFalse(json.get("enabled").asBoolean());
        assertEquals(7000, json.get("timeout").asInt());
        assertEquals(75000, json.get("statementLimit").asLong());
    }

    // ── Capability wiring ────────────────────────────────────────

    @Test
    public void capabilityShouldExposeScriptingSpec() {
        ScriptingManagementSpec scripting = capability.getScriptingSpec();

        assertNotNull(scripting);
        assertTrue(scripting.isEnabled());
        assertEquals("file:///app/scripts", scripting.getDefaultLocation());
    }

    @Test
    public void capabilityShouldHaveNullScriptingSpecWhenNotConfigured() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(
                new File("src/test/resources/control/control-capability.yaml"),
                IkanosSpec.class);
        Capability capWithout = new Capability(spec);

        assertNull(capWithout.getScriptingSpec());
    }
}
