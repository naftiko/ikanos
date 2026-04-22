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
package io.naftiko.engine.exposes;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import io.naftiko.engine.util.StepExecutionContext;
import io.naftiko.spec.exposes.OperationStepScriptSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScriptStepExecutor.
 * Tests JavaScript and Groovy execution, result binding, type mapping, with injection,
 * security, and file resolution.
 */
class ScriptStepExecutorTest {

    private static String scriptsLocationUri;

    private final ScriptStepExecutor executor = new ScriptStepExecutor();

    @BeforeAll
    static void resolveScriptsLocation() {
        File scriptsDir = new File("src/test/resources/scripts");
        scriptsLocationUri = scriptsDir.toURI().toString();
        // Normalize to file:/// format
        if (!scriptsLocationUri.startsWith("file:///")) {
            scriptsLocationUri = scriptsLocationUri.replace("file:/", "file:///");
        }
    }

    // ── JavaScript execution ─────────────────────────────────────

    @Test
    void executeShouldRunJavaScriptAndReturnResult() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "transform", "javascript", scriptsLocationUri, "simple-transform.js");

        JsonNode result = executor.execute(step, new HashMap<>(), new StepExecutionContext());

        assertNotNull(result);
        assertTrue(result.isObject());
        assertEquals("hello", result.get("greeting").asText());
        assertEquals(42, result.get("count").asInt());
        assertTrue(result.get("flag").asBoolean());
    }

    @Test
    void executeShouldFilterArrayFromStepContext() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "filter-active", "javascript", scriptsLocationUri, "filter-active.js");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode members = mapper.createArrayNode();
        members.add(mapper.createObjectNode().put("login", "alice").put("id", 1).put("type", "User"));
        members.add(mapper.createObjectNode().put("login", "bot-ci").put("id", 2).put("type", "Bot"));
        members.add(mapper.createObjectNode().put("login", "bob").put("id", 3).put("type", "User"));
        stepContext.storeStepOutput("list-members", members);

        Map<String, Object> runtimeParams = new HashMap<>();

        JsonNode result = executor.execute(step, runtimeParams, stepContext);

        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).get("login").asText());
        assertEquals("bob", result.get(1).get("login").asText());
    }

    @Test
    void executeShouldComputeDerivedValues() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "compute-totals", "javascript", scriptsLocationUri, "compute-totals.js");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode orders = mapper.createArrayNode();
        orders.add(mapper.createObjectNode().put("amount", 10.5));
        orders.add(mapper.createObjectNode().put("amount", 20.0));
        orders.add(mapper.createObjectNode().put("amount", 30.0));
        stepContext.storeStepOutput("get-orders", orders);

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertNotNull(result);
        assertEquals(60.5, result.get("totalAmount").asDouble(), 0.001);
        assertEquals(3, result.get("orderCount").asInt());
    }

    @Test
    void executeShouldInjectWithParameters() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "filter-by-threshold", "javascript", scriptsLocationUri,
                "filter-by-threshold.js", null,
                Map.of("threshold", "{{minStock}}"));

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode items = mapper.createArrayNode();
        items.add(mapper.createObjectNode().put("name", "Widget").put("stock", 5));
        items.add(mapper.createObjectNode().put("name", "Gadget").put("stock", 15));
        items.add(mapper.createObjectNode().put("name", "Gizmo").put("stock", 3));
        stepContext.storeStepOutput("fetch-items", items);

        Map<String, Object> runtimeParams = new HashMap<>();
        runtimeParams.put("minStock", 10);

        JsonNode result = executor.execute(step, runtimeParams, stepContext);

        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
        assertEquals("Gizmo", result.get(1).get("name").asText());
    }

    @Test
    void executeShouldReturnNullWhenResultNotAssigned() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "no-result", "javascript", scriptsLocationUri, "no-result.js");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        stepContext.storeStepOutput("some-step",
                mapper.createObjectNode().put("key", "value"));

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        // When result is not assigned, GraalVM returns null for the member
        assertTrue(result == null || result.isNull());
    }

    // ── JavaScript dependencies ──────────────────────────────────

    @Test
    void executeShouldPreEvaluateDependencies() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "filter-with-deps", "javascript", scriptsLocationUri,
                "active-members.js", List.of("lib/array-utils.js"), null);

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode members = mapper.createArrayNode();
        members.add(mapper.createObjectNode().put("login", "alice").put("id", 1).put("type", "User"));
        members.add(mapper.createObjectNode().put("login", "bot-ci").put("id", 2).put("type", "Bot"));
        members.add(mapper.createObjectNode().put("login", "bob").put("id", 3).put("type", "User"));
        stepContext.storeStepOutput("list-members", members);

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).get("login").asText());
        assertEquals(1, result.get(0).get("id").asInt());
        assertEquals("bob", result.get(1).get("login").asText());
    }

    // ── Groovy execution ─────────────────────────────────────────

    @Test
    void executeShouldRunGroovyScript() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "enrich", "groovy", scriptsLocationUri, "enrich-products.groovy");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode products = mapper.createArrayNode();
        products.add(mapper.createObjectNode().put("name", "Widget").put("price", 100.0));
        products.add(mapper.createObjectNode().put("name", "Gadget").put("price", 50.0));
        stepContext.storeStepOutput("fetch-products", products);

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
        assertEquals(90.0, result.get(0).get("discounted").asDouble(), 0.001);
    }

    // ── Security ─────────────────────────────────────────────────

    @Test
    void executeShouldEnforceStatementLimit() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "infinite", "javascript", scriptsLocationUri, "infinite-loop.js");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    @Test
    void executeShouldDenyHostAccess() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "host-access", "javascript", scriptsLocationUri, "host-access.js");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    // ── File resolution ──────────────────────────────────────────

    @Test
    void executeShouldRejectPathTraversal() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "traversal", "javascript", scriptsLocationUri, "../../etc/passwd");

        assertThrows(SecurityException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    @Test
    void executeShouldFailOnMissingFile() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "missing", "javascript", scriptsLocationUri, "nonexistent.js");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    @Test
    void executeShouldFailOnMissingDependency() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "missing-dep", "javascript", scriptsLocationUri,
                "simple-transform.js", List.of("nonexistent-dep.js"), null);

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    // ── Validation ───────────────────────────────────────────────

    @Test
    void executeShouldFailWhenLocationIsMissing() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "no-loc", "javascript", null, "script.js");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    @Test
    void executeShouldFailWhenLanguageIsMissing() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "no-lang", null, scriptsLocationUri, "simple-transform.js");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    // ── Bindings ─────────────────────────────────────────────────

    @Test
    void buildBindingsShouldMergeRuntimeParamsAndStepOutputs() {
        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        stepContext.storeStepOutput("step-a",
                mapper.createObjectNode().put("field", "value"));

        Map<String, Object> runtimeParams = new HashMap<>();
        runtimeParams.put("param1", "val1");

        Map<String, Object> bindings =
                executor.buildBindings(runtimeParams, stepContext, null);

        assertEquals("val1", bindings.get("param1"));
        assertNotNull(bindings.get("step-a"));
    }

    // ── Python execution ─────────────────────────────────────────

    @Test
    void executeShouldRunPythonAndReturnResult() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "transform", "python", scriptsLocationUri, "simple-transform.py");

        JsonNode result = executor.execute(step, new HashMap<>(), new StepExecutionContext());

        assertNotNull(result);
        assertTrue(result.isObject());
        assertEquals("hello", result.get("greeting").asText());
        assertEquals(42, result.get("count").asInt());
        assertTrue(result.get("flag").asBoolean());
    }

    @Test
    void executeShouldFilterArrayWithPython() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "filter-active", "python", scriptsLocationUri, "filter-active.py");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode members = mapper.createArrayNode();
        members.add(mapper.createObjectNode().put("login", "alice").put("id", 1).put("type", "User"));
        members.add(mapper.createObjectNode().put("login", "bot-ci").put("id", 2).put("type", "Bot"));
        members.add(mapper.createObjectNode().put("login", "bob").put("id", 3).put("type", "User"));
        stepContext.storeStepOutput("list-members", members);

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).get("login").asText());
        assertEquals("bob", result.get(1).get("login").asText());
    }

    @Test
    void executeShouldComputeStatisticsWithPython() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "analyze", "python", scriptsLocationUri, "analyze-readings.py");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode readings = mapper.createArrayNode();
        readings.add(mapper.createObjectNode().put("temperature", 20.0));
        readings.add(mapper.createObjectNode().put("temperature", 25.0));
        readings.add(mapper.createObjectNode().put("temperature", 30.0));
        stepContext.storeStepOutput("fetch-readings", readings);

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertNotNull(result);
        assertEquals(25.0, result.get("average").asDouble(), 0.001);
        assertEquals(20.0, result.get("min").asDouble(), 0.001);
        assertEquals(30.0, result.get("max").asDouble(), 0.001);
        assertEquals(3, result.get("count").asInt());
    }

    @Test
    void executeShouldInjectWithParametersInPython() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "filter-by-threshold", "python", scriptsLocationUri,
                "filter-by-threshold.py", null,
                Map.of("threshold", "{{minStock}}"));

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode items = mapper.createArrayNode();
        items.add(mapper.createObjectNode().put("name", "Widget").put("stock", 5));
        items.add(mapper.createObjectNode().put("name", "Gadget").put("stock", 15));
        items.add(mapper.createObjectNode().put("name", "Gizmo").put("stock", 3));
        stepContext.storeStepOutput("fetch-items", items);

        Map<String, Object> runtimeParams = new HashMap<>();
        runtimeParams.put("minStock", 10);

        JsonNode result = executor.execute(step, runtimeParams, stepContext);

        assertNotNull(result);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
        assertEquals("Gizmo", result.get(1).get("name").asText());
    }

    @Test
    void executeShouldReturnNullWhenPythonResultNotAssigned() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "no-result", "python", scriptsLocationUri, "no-result.py");

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        stepContext.storeStepOutput("some-step",
                mapper.createObjectNode().put("key", "value"));

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertTrue(result == null || result.isNull());
    }

    // ── Python dependencies ──────────────────────────────────────

    @Test
    void executeShouldPreEvaluatePythonDependencies() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "analyze-with-deps", "python", scriptsLocationUri,
                "analyze-with-deps.py", List.of("lib/stats.py"), null);

        StepExecutionContext stepContext = new StepExecutionContext();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode readings = mapper.createArrayNode();
        readings.add(mapper.createObjectNode().put("temperature", 20.0));
        readings.add(mapper.createObjectNode().put("temperature", 25.0));
        readings.add(mapper.createObjectNode().put("temperature", 30.0));
        stepContext.storeStepOutput("fetch-readings", readings);

        JsonNode result = executor.execute(step, new HashMap<>(), stepContext);

        assertNotNull(result);
        assertEquals(25.0, result.get("average").asDouble(), 0.001);
        assertEquals(3, result.get("count").asInt());
    }

    // ── Python security ──────────────────────────────────────────

    @Test
    void executeShouldDenyHostAccessInPython() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "host-access", "python", scriptsLocationUri, "host-access.py");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

    @Test
    void executeShouldEnforceStatementLimitInPython() {
        OperationStepScriptSpec step = new OperationStepScriptSpec(
                "infinite", "python", scriptsLocationUri, "infinite-loop.py");

        assertThrows(IllegalArgumentException.class, () ->
                executor.execute(step, new HashMap<>(), new StepExecutionContext()));
    }

}
