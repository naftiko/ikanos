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
package io.naftiko.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.exposes.McpServerPromptSpec;
import io.naftiko.spec.exposes.McpServerResourceSpec;
import io.naftiko.spec.exposes.McpServerSpec;
import io.naftiko.spec.exposes.McpServerToolSpec;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Integration tests for MCP Server Adapter with resources and prompts support.
 * Tests YAML deserialization, spec wiring, handler creation, and protocol dispatch.
 */
public class ResourcesPromptsIntegrationTest {

    private Capability capability;
    private McpServerAdapter adapter;
    private ObjectMapper jsonMapper;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/mcp-resources-prompts-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(),
                "MCP resources/prompts capability test file should exist at " + resourcePath);

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = yamlMapper.readValue(file, NaftikoSpec.class);

        capability = new Capability(spec);
        adapter = (McpServerAdapter) capability.getServerAdapters().get(0);
        jsonMapper = new ObjectMapper();
    }

    // ── Capability loading ────────────────────────────────────────────────────────────────────────

    @Test
    public void testCapabilityLoaded() {
        assertNotNull(capability, "Capability should be initialized");
        assertEquals("1.0.0-alpha1", capability.getSpec().getNaftiko(), "Naftiko version should be 0.5");
    }

    @Test
    public void testMcpServerAdapterCreated() {
        assertNotNull(adapter, "McpServerAdapter should be created");
    }

    // ── Spec deserialization ──────────────────────────────────────────────────────────────────────

    @Test
    public void testResourceSpecsDeserialized() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        List<McpServerResourceSpec> resources = spec.getResources();

        assertEquals(2, resources.size(), "Should have exactly 2 resource specs");
    }

    @Test
    public void testStaticLikeResourceSpecFields() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        McpServerResourceSpec dbSchema = spec.getResources().stream()
                .filter(r -> "database-schema".equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("database-schema resource not found"));

        assertEquals("database-schema", dbSchema.getName());
        assertEquals("Database Schema", dbSchema.getLabel());
        assertEquals("data://databases/pre-release/schema", dbSchema.getUri());
        assertTrue(dbSchema.getDescription().contains("pre-release participants database"));
        assertEquals("application/json", dbSchema.getMimeType());
        assertNotNull(dbSchema.getCall(), "Should have a call spec");
        assertEquals("mock-api.query-db", dbSchema.getCall().getOperation());
        assertFalse(dbSchema.isStatic(), "Dynamic resource should not be static");
        assertFalse(dbSchema.isTemplate(), "Non-template URI should not be a template");
    }

    @Test
    public void testTemplateResourceSpecFields() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        McpServerResourceSpec userProfile = spec.getResources().stream()
                .filter(r -> "user-profile".equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("user-profile resource not found"));

        assertEquals("User Profile", userProfile.getLabel());
        assertEquals("data://users/{userId}/profile", userProfile.getUri());
        assertTrue(userProfile.isTemplate(), "URI with {param} should be a template");
        assertFalse(userProfile.isStatic(), "Dynamic resource should not be static");
    }

    @Test
    public void testPromptSpecsDeserialized() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        List<McpServerPromptSpec> prompts = spec.getPrompts();

        assertEquals(2, prompts.size(), "Should have exactly 2 prompt specs");
    }

    @Test
    public void testParticipantOutreachPromptFields() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        McpServerPromptSpec outreach = spec.getPrompts().stream()
                .filter(p -> "participant-outreach".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("participant-outreach prompt not found"));

        assertEquals("Participant Outreach", outreach.getLabel());
        assertTrue(outreach.getDescription().contains("pre-release participants"));
        assertFalse(outreach.isFileBased(), "Inline prompt should not be file-based");
        assertEquals(2, outreach.getArguments().size(), "Should have 2 arguments");
        assertEquals(1, outreach.getTemplate().size(), "Should have 1 template message");

        var participantArg = outreach.getArguments().stream()
                .filter(a -> "participant_name".equals(a.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(participantArg.isRequired(), "participant_name should be required");
    }

    @Test
    public void testSummaryPromptMultiTurnTemplate() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        McpServerPromptSpec summary = spec.getPrompts().stream()
                .filter(p -> "summary-prompt".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("summary-prompt not found"));

        assertEquals(3, summary.getTemplate().size(), "Should have 3 template messages");

        // Verify role sequence: user, assistant, user
        assertEquals("user", summary.getTemplate().get(0).getRole());
        assertEquals("assistant", summary.getTemplate().get(1).getRole());
        assertEquals("user", summary.getTemplate().get(2).getRole());

        // format argument is optional
        var formatArg = summary.getArguments().stream()
                .filter(a -> "format".equals(a.getName()))
                .findFirst()
                .orElseThrow();
        assertFalse(formatArg.isRequired(), "format argument should be optional");
    }

    @Test
    public void testToolLabelDeserialized() {
        McpServerSpec spec = adapter.getMcpServerSpec();
        McpServerToolSpec tool = spec.getTools().get(0);

        assertEquals("Query Database", tool.getLabel(), "Tool label should be deserialized");
    }

    // ── Handler wiring ────────────────────────────────────────────────────────────────────────────

    @Test
    public void testResourceHandlerCreated() {
        assertNotNull(adapter.getResourceHandler(), "McpResourceHandler should be created");
    }

    @Test
    public void testPromptHandlerCreated() {
        assertNotNull(adapter.getPromptHandler(), "McpPromptHandler should be created");
    }

    @Test
    public void testToolLabelsMap() {
        Map<String, String> labels = adapter.getToolLabels();
        assertNotNull(labels, "Tool labels map should not be null");
        assertEquals("Query Database", labels.get("query-database"),
                "Tool label should be in the labels map");
    }

    // ── MCP protocol: initialize ──────────────────────────────────────────────────────────────────

    @Test
    public void testInitializeAdvertisesResourcesAndPrompts() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        assertNotNull(response, "initialize should return a response");
        JsonNode result = response.path("result");

        JsonNode capabilities = result.path("capabilities");
        assertFalse(capabilities.isMissingNode(), "capabilities should be present");
        assertFalse(capabilities.path("resources").isMissingNode(),
                "resources capability should be advertised when resources are declared");
        assertFalse(capabilities.path("prompts").isMissingNode(),
                "prompts capability should be advertised when prompts are declared");
    }

    // ── MCP protocol: tools/list ──────────────────────────────────────────────────────────────────

    @Test
    public void testToolsListIncludesTitle() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        JsonNode tools = response.path("result").path("tools");
        assertFalse(tools.isMissingNode(), "tools array should be present");
        assertTrue(tools.isArray() && tools.size() > 0, "Should have at least one tool");

        JsonNode tool = tools.get(0);
        assertEquals("query-database", tool.path("name").asText());
        assertEquals("Query Database", tool.path("title").asText(),
                "title field should be present from label");
    }

    // ── MCP protocol: resources/list ─────────────────────────────────────────────────────────────

    @Test
    public void testResourcesListReturnsConcrete() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":3,"method":"resources/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        JsonNode resources = response.path("result").path("resources");
        assertFalse(resources.isMissingNode(), "resources array should be present");

        // database-schema (non-template) should be in the list
        boolean found = false;
        for (JsonNode r : resources) {
            if ("data://databases/pre-release/schema".equals(r.path("uri").asText())) {
                found = true;
                assertEquals("database-schema", r.path("name").asText());
                assertEquals("Database Schema", r.path("title").asText());
                assertEquals("application/json", r.path("mimeType").asText());
            }
        }
        assertTrue(found, "database-schema resource should appear in resources/list");
    }

    @Test
    public void testResourcesListExcludesTemplates() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":4,"method":"resources/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        JsonNode resources = response.path("result").path("resources");
        for (JsonNode r : resources) {
            assertFalse(r.path("uri").asText().contains("{"),
                    "resources/list should not include template URIs");
        }
    }

    // ── MCP protocol: resources/templates/list ───────────────────────────────────────────────────

    @Test
    public void testResourcesTemplatesList() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":5,"method":"resources/templates/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        JsonNode templates = response.path("result").path("resourceTemplates");
        assertFalse(templates.isMissingNode(), "resourceTemplates should be present");
        assertTrue(templates.isArray() && templates.size() > 0,
                "Should have at least one resource template");

        JsonNode tmpl = templates.get(0);
        assertEquals("data://users/{userId}/profile", tmpl.path("uriTemplate").asText(),
                "uriTemplate should carry the raw URI with {param}");
        assertEquals("user-profile", tmpl.path("name").asText());
        assertEquals("User Profile", tmpl.path("title").asText());
    }

    // ── MCP protocol: prompts/list ────────────────────────────────────────────────────────────────

    @Test
    public void testPromptsListReturnsBothPrompts() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":6,"method":"prompts/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        JsonNode prompts = response.path("result").path("prompts");
        assertFalse(prompts.isMissingNode(), "prompts array should be present");
        assertEquals(2, prompts.size(), "Should return exactly 2 prompts");
    }

    @Test
    public void testPromptsListIncludesTitleAndArguments() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":7,"method":"prompts/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);

        JsonNode prompts = response.path("result").path("prompts");
        JsonNode outreach = null;
        for (JsonNode p : prompts) {
            if ("participant-outreach".equals(p.path("name").asText())) {
                outreach = p;
            }
        }
        assertNotNull(outreach, "participant-outreach should be in prompts list");
        assertEquals("Participant Outreach", outreach.path("title").asText());

        JsonNode args = outreach.path("arguments");
        assertTrue(args.isArray() && args.size() == 2, "Should have 2 arguments");

        boolean foundParticipant = false;
        for (JsonNode arg : args) {
            if ("participant_name".equals(arg.path("name").asText())) {
                foundParticipant = true;
                assertTrue(arg.path("required").asBoolean(), "participant_name should be required");
            }
        }
        assertTrue(foundParticipant, "participant_name argument should be listed");
    }

    @Test
    public void testPromptsListArgumentTitleEmitted() throws Exception {
        // PromptArgument.title is required by the 2025-11-25 schema when a label is declared.
        // Our YAML fixture declares arguments without an explicit 'label', so title should be
        // absent. This test verifies presence/absence matches the label declaration.
        String requestJson = """
                {"jsonrpc":"2.0","id":99,"method":"prompts/list","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);
        JsonNode prompts = response.path("result").path("prompts");

        // The YAML arguments have no 'label' field declared, so 'title' must NOT appear.
        for (JsonNode p : prompts) {
            for (JsonNode arg : p.path("arguments")) {
                assertTrue(arg.path("title").isMissingNode(),
                        "title should be absent for arguments that have no label declared");
            }
        }
    }

    // ── MCP protocol: prompts/get ─────────────────────────────────────────────────────────────────

    @Test
    public void testPromptsGetRendersInlineTemplate() throws Exception {
        String requestJson = """
                {
                  "jsonrpc": "2.0",
                  "id": 8,
                  "method": "prompts/get",
                  "params": {
                    "name": "participant-outreach",
                    "arguments": {
                      "participant_name": "Alice",
                      "product_name": "Naftiko"
                    }
                  }
                }
                """;
        ObjectNode response = dispatch(requestJson);

        assertNull(response.get("error"), "prompts/get should not return an error");
        JsonNode messages = response.path("result").path("messages");
        assertTrue(messages.isArray() && messages.size() == 1, "Should have 1 rendered message");

        JsonNode msg = messages.get(0);
        assertEquals("user", msg.path("role").asText());

        JsonNode content = msg.path("content");
        String text = content.path("text").asText();
        assertTrue(text.contains("Alice"), "Rendered message should contain participant_name");
        assertTrue(text.contains("Naftiko"), "Rendered message should contain product_name");
        assertFalse(text.contains("{{"), "No unrendered placeholders should remain");
    }

    @Test
    public void testPromptsGetMultiTurnTemplate() throws Exception {
        String requestJson = """
                {
                  "jsonrpc": "2.0",
                  "id": 9,
                  "method": "prompts/get",
                  "params": {
                    "name": "summary-prompt",
                    "arguments": {
                      "data": "item1, item2, item3",
                      "format": "bullet-points"
                    }
                  }
                }
                """;
        ObjectNode response = dispatch(requestJson);

        assertNull(response.get("error"), "prompts/get should not return an error");
        JsonNode messages = response.path("result").path("messages");
        assertEquals(3, messages.size(), "Should have 3 rendered messages");

        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals("user", messages.get(2).path("role").asText());

        String firstText = messages.get(0).path("content").path("text").asText();
        assertTrue(firstText.contains("bullet-points"), "format placeholder should be substituted");
        assertTrue(firstText.contains("item1, item2, item3"), "data placeholder should be substituted");
    }

    @Test
    public void testPromptsGetMissingArgumentLeavesPlaceholder() throws Exception {
        // Omit format — optional arg; placeholder should be left if not provided
        String requestJson = """
                {
                  "jsonrpc": "2.0",
                  "id": 10,
                  "method": "prompts/get",
                  "params": {
                    "name": "summary-prompt",
                    "arguments": {
                      "data": "some data"
                    }
                  }
                }
                """;
        ObjectNode response = dispatch(requestJson);

        assertNull(response.get("error"), "Missing optional arg should not cause an error");
        JsonNode messages = response.path("result").path("messages");
        String firstText = messages.get(0).path("content").path("text").asText();
        // {{format}} should remain since it was not provided
        assertTrue(firstText.contains("{{format}}"),
                "Unresolved optional placeholder should remain as-is");
    }

    @Test
    public void testPromptsGetUnknownPromptReturnsError() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":11,"method":"prompts/get","params":{"name":"does-not-exist"}}
                """;
        ObjectNode response = dispatch(requestJson);

        assertNotNull(response.path("error"), "Unknown prompt should return JSON-RPC error");
        assertFalse(response.path("error").isMissingNode(),
                "Error field should be present for unknown prompt");
    }

    // ── McpResourceHandler unit tests ─────────────────────────────────────────────────────────────

    @Test
    public void testMatchTemplateExactNonTemplate() {
        Map<String, String> result = ResourceHandler.matchTemplate(
                "data://databases/schema", "data://databases/schema");
        assertNotNull(result, "Exact match should succeed");
        assertTrue(result.isEmpty(), "No params for a non-template URI");
    }

    @Test
    public void testMatchTemplateNoMatch() {
        Map<String, String> result = ResourceHandler.matchTemplate(
                "data://databases/schema", "data://databases/other");
        assertNull(result, "Different URI should not match");
    }

    @Test
    public void testMatchTemplateExtracts() {
        Map<String, String> result = ResourceHandler.matchTemplate(
                "data://users/{userId}/profile", "data://users/42/profile");
        assertNotNull(result, "Template match should succeed");
        assertEquals("42", result.get("userId"), "userId param should be extracted");
    }

    @Test
    public void testMatchTemplateMultipleParams() {
        Map<String, String> result = ResourceHandler.matchTemplate(
                "data://orgs/{orgId}/users/{userId}", "data://orgs/acme/users/bob");
        assertNotNull(result, "Multi-param match should succeed");
        assertEquals("acme", result.get("orgId"));
        assertEquals("bob", result.get("userId"));
    }

    @Test
    public void testMatchTemplateDoesNotCrossSegmentBoundary() {
        // {userId} should not match across '/'
        Map<String, String> result = ResourceHandler.matchTemplate(
                "data://users/{userId}/profile", "data://users/a/b/profile");
        assertNull(result, "{param} should not span path separators");
    }

    // ── McpPromptHandler unit tests ───────────────────────────────────────────────────────────────

    @Test
    public void testSubstituteBasic() {
        Map<String, String> args = Map.of("name", "Alice", "product", "Naftiko");
        String result = PromptHandler.substitute("Hello {{name}}, welcome to {{product}}!", args);
        assertEquals("Hello Alice, welcome to Naftiko!", result);
    }

    @Test
    public void testSubstituteUnknownPlaceholderUnchanged() {
        Map<String, String> args = Map.of("name", "Alice");
        String result = PromptHandler.substitute("Hello {{name}} from {{team}}!", args);
        assertEquals("Hello Alice from {{team}}!", result,
                "Unresolved placeholder should be left as-is");
    }

    @Test
    public void testSubstituteInjectionPrevention() {
        // An attacker-controlled value that itself looks like a placeholder
        Map<String, String> args = Map.of("greeting", "Hi {{admin}}");
        String result = PromptHandler.substitute("{{greeting}} there", args);
        // The value should appear literally — not be re-interpolated
        assertEquals("Hi {{admin}} there", result,
                "Placeholder syntax inside argument values must not be re-interpolated");
    }

    @Test
    public void testSubstituteNullTemplate() {
        String result = PromptHandler.substitute(null, Map.of("x", "y"));
        assertEquals("", result, "null template should produce empty string");
    }

    @Test
    public void testSubstituteBackslashAndDollarSafe() {
        // Values containing regex special chars ($ and \) should be treated as literals
        Map<String, String> args = Map.of("value", "C:\\Users\\$HOME");
        String result = PromptHandler.substitute("Path: {{value}}", args);
        assertEquals("Path: C:\\Users\\$HOME", result,
                "Backslash and dollar sign in argument values must be literal");
    }

    // ── Protocol error handling ───────────────────────────────────────────────────────────────────

    @Test
    public void testResourcesReadMissingUriReturnsError() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":12,"method":"resources/read","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);
        assertFalse(response.path("error").isMissingNode(),
                "Missing uri should return JSON-RPC error");
    }

    @Test
    public void testResourcesReadUnknownUriReturnsError() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":13,"method":"resources/read","params":{"uri":"data://does-not-exist"}}
                """;
        ObjectNode response = dispatch(requestJson);
        assertFalse(response.path("error").isMissingNode(),
                "Unknown URI should return JSON-RPC error");
    }

    @Test
    public void testPromptsGetMissingNameReturnsError() throws Exception {
        String requestJson = """
                {"jsonrpc":"2.0","id":14,"method":"prompts/get","params":{}}
                """;
        ObjectNode response = dispatch(requestJson);
        assertFalse(response.path("error").isMissingNode(),
                "Missing name should return JSON-RPC error");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    private ObjectNode dispatch(String requestJson) throws Exception {
        JsonNode request = jsonMapper.readTree(requestJson);
        var dispatcher = new io.naftiko.engine.exposes.mcp.ProtocolDispatcher(adapter);
        return dispatcher.dispatch(request);
    }
}
