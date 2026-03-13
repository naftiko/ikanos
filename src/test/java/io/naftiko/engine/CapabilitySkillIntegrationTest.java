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
package io.naftiko.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.engine.exposes.ServerAdapter;
import io.naftiko.engine.exposes.SkillServerAdapter;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.SkillServerSpec;

/**
 * Integration tests for the Skill Server Adapter.
 *
 * <p>Verifies YAML deserialization, adapter wiring, lifecycle (start/stop), and live HTTP
 * responses from the skill catalog endpoints.</p>
 */
public class CapabilitySkillIntegrationTest {

    private static final int SKILL_PORT = 9097;
    private static final String BASE_URL = "http://localhost:" + SKILL_PORT;

    private static Capability capability;
    private static SkillServerAdapter skillAdapter;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void setUp() throws Exception {
        File file = new File("src/test/resources/skill-capability.yaml");
        assertTrue(file.exists(), "Skill capability fixture should exist");

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(file, NaftikoSpec.class);

        capability = new Capability(spec);

        skillAdapter = (SkillServerAdapter) capability.getServerAdapters().stream()
                .filter(a -> a instanceof SkillServerAdapter)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SkillServerAdapter found"));

        skillAdapter.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (skillAdapter != null) {
            skillAdapter.stop();
        }
    }

    // --- Spec wiring tests ---

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability.getSpec());
        assertEquals("0.5", capability.getSpec().getNaftiko());
    }

    @Test
    public void testSkillAdapterCreated() {
        List<ServerAdapter> adapters = capability.getServerAdapters();
        assertFalse(adapters.isEmpty());
        assertTrue(adapters.stream().anyMatch(a -> a instanceof SkillServerAdapter),
                "Should have a SkillServerAdapter");
    }

    @Test
    public void testSkillServerSpecDeserialized() {
        SkillServerSpec spec = skillAdapter.getSkillServerSpec();
        assertEquals("skill", spec.getType());
        assertEquals("localhost", spec.getAddress());
        assertEquals(SKILL_PORT, spec.getPort());
        assertEquals("orders-skills", spec.getNamespace());
        assertEquals(2, spec.getSkills().size());
    }

    @Test
    public void testFirstSkillSpec() {
        SkillServerSpec spec = skillAdapter.getSkillServerSpec();
        var skill = spec.getSkills().get(0);
        assertEquals("order-management", skill.getName());
        assertEquals(2, skill.getTools().size());
        assertEquals("orders-rest", skill.getTools().get(0).getFrom().getSourceNamespace());
        assertEquals("order-guide.md", skill.getTools().get(1).getInstruction());
    }

    // --- HTTP endpoint tests ---

    @Test
    @SuppressWarnings("unchecked")
    public void testGetSkillsCatalog() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/skills"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());

        Map<String, Object> body = JSON.readValue(response.body(), Map.class);
        assertEquals(2, body.get("count"));

        List<Map<String, Object>> skills = (List<Map<String, Object>>) body.get("skills");
        assertNotNull(skills);
        assertEquals(2, skills.size());
        assertEquals("order-management", skills.get(0).get("name"));
        assertEquals("onboarding-guide", skills.get(1).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetSkillDetail() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/skills/order-management"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        Map<String, Object> body = JSON.readValue(response.body(), Map.class);
        assertEquals("order-management", body.get("name"));
        assertEquals("Tools for managing orders", body.get("description"));
        assertEquals("list-orders", body.get("allowed-tools"));
        assertEquals("Use for order operations", body.get("argument-hint"));

        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertNotNull(tools);
        assertEquals(2, tools.size());

        // Derived tool has invocationRef
        Map<String, Object> derivedTool = tools.get(0);
        assertEquals("list-orders", derivedTool.get("name"));
        assertEquals("derived", derivedTool.get("type"));
        Map<String, Object> invRef = (Map<String, Object>) derivedTool.get("invocationRef");
        assertNotNull(invRef);
        assertEquals("orders-rest", invRef.get("targetNamespace"));
        assertEquals("list-orders", invRef.get("action"));
        assertEquals("rest", invRef.get("mode"));

        // Instruction tool has instruction path
        Map<String, Object> instructionTool = tools.get(1);
        assertEquals("order-guide", instructionTool.get("name"));
        assertEquals("instruction", instructionTool.get("type"));
        assertEquals("order-guide.md", instructionTool.get("instruction"));
    }

    @Test
    public void testGetUnknownSkillReturns404() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/skills/unknown-skill"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    public void testGetDescriptiveSkillNoTools() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/skills/onboarding-guide"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(response.body(), Map.class);
        assertEquals("onboarding-guide", body.get("name"));

        List<?> tools = (List<?>) body.get("tools");
        assertNotNull(tools);
        assertTrue(tools.isEmpty(), "Descriptive skill should have empty tools array");
    }
}
