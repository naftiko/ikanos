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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.Method;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.InputParameterSpec;

public class ResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Regression test for: nested object property without a top-level mapping (e.g. "specs") must
     * recurse into its own properties and resolve them against the same root, instead of returning
     * null.
     *
     * <p>Scenario: a "get-ship" output spec declares a "specs" property of type object with no
     * mapping key. Its children (yearBuilt, tonnage, length) each have their own JSONPath mappings
     * that point to flat fields on the API response ($.year_built, $.gross_tonnage,
     * $.dimensions.length_overall). Before the fix, "specs" was always resolved as NullNode
     * because the resolver tried to jsonPathExtract with a null mapping.</p>
     */
    @Test
    public void resolveOutputMappingsShouldRecurseIntoNestedObjectWithoutTopLevelMapping()
            throws Exception {
        JsonNode apiResponse = MAPPER.readTree("""
                {
                  "imo_number": "IMO-9321483",
                  "vessel_name": "Northern Star",
                  "vessel_type": "cargo",
                  "flag_code": "NO",
                  "operational_status": "active",
                  "year_built": 2015,
                  "gross_tonnage": 42000,
                  "dimensions": {
                    "length_overall": 229
                  }
                }
                """);

        // Build the "specs" sub-spec (type object, no mapping, has properties)
        OutputParameterSpec yearBuilt = new OutputParameterSpec("yearBuilt", "number", null, "$.year_built");
        OutputParameterSpec tonnage = new OutputParameterSpec("tonnage", "number", null, "$.gross_tonnage");
        OutputParameterSpec length = new OutputParameterSpec("length", "number", null, "$.dimensions.length_overall");

        OutputParameterSpec specsSpec = new OutputParameterSpec();
        specsSpec.setName("specs");
        specsSpec.setType("object");
        specsSpec.getProperties().addAll(List.of(yearBuilt, tonnage, length));

        // Build the root object spec
        OutputParameterSpec imoSpec = new OutputParameterSpec("imo", "string", null, "$.imo_number");
        OutputParameterSpec nameSpec = new OutputParameterSpec("name", "string", null, "$.vessel_name");
        OutputParameterSpec statusSpec = new OutputParameterSpec("status", "string", null, "$.operational_status");

        OutputParameterSpec rootSpec = new OutputParameterSpec();
        rootSpec.setType("object");
        rootSpec.getProperties().addAll(List.of(imoSpec, nameSpec, statusSpec, specsSpec));

        JsonNode result = Resolver.resolveOutputMappings(rootSpec, apiResponse, MAPPER);

        assertNotNull(result);
        assertTrue(result.isObject());
        assertEquals("IMO-9321483", result.path("imo").asText());
        assertEquals("Northern Star", result.path("name").asText());
        assertEquals("active", result.path("status").asText());

        JsonNode specs = result.path("specs");
        assertFalse(specs.isMissingNode(), "specs must be present");
        assertFalse(specs.isNull(), "specs must not be null");
        assertTrue(specs.isObject(), "specs must be an object");
        assertEquals(2015, specs.path("yearBuilt").asInt());
        assertEquals(42000, specs.path("tonnage").asInt());
        assertEquals(229, specs.path("length").asInt());
    }

      @Test
      public void resolveMustacheTemplateShouldHandleNullAndEmptyParameters() {
        assertNull(Resolver.resolveMustacheTemplate(null, Map.of()));
        assertEquals("plain", Resolver.resolveMustacheTemplate("plain", null));
        assertEquals("plain", Resolver.resolveMustacheTemplate("plain", Map.of()));

        String rendered = Resolver.resolveMustacheTemplate("hello {{name}}/{{missing}}",
            Map.of("name", "alice"));
        assertEquals("hello alice/", rendered);
      }

      @Test
      public void resolveInputParameterFromRequestShouldHandlePathQueryHeaderAndBody() throws Exception {
        Request request = new Request(Method.GET, "https://example.com/search?q=ships");
        request.getAttributes().put("shipId", "IMO-1");
        request.getHeaders().set("X-Tenant", "acme");

        JsonNode body = MAPPER.readTree("{\"name\":\"Voyager\",\"meta\":{\"rank\":1}}\n");

        InputParameterSpec path = new InputParameterSpec();
        path.setName("shipId");
        path.setIn("path");

        InputParameterSpec query = new InputParameterSpec();
        query.setName("q");
        query.setIn("query");

        InputParameterSpec header = new InputParameterSpec();
        header.setName("X-Tenant");
        header.setIn("header");

        InputParameterSpec bodyJsonPath = new InputParameterSpec();
        bodyJsonPath.setName("name");
        bodyJsonPath.setIn("body");
        bodyJsonPath.setValue("$.name");

        InputParameterSpec bodyObject = new InputParameterSpec();
        bodyObject.setName("meta");
        bodyObject.setIn("body");
        bodyObject.setValue("$.meta");

        assertEquals("IMO-1", Resolver.resolveInputParameterFromRequest(path, request, body, MAPPER));
        assertEquals("ships", Resolver.resolveInputParameterFromRequest(query, request, body, MAPPER));
        assertEquals("acme", Resolver.resolveInputParameterFromRequest(header, request, body, MAPPER));
        assertEquals("Voyager",
            Resolver.resolveInputParameterFromRequest(bodyJsonPath, request, body, MAPPER));

        Object meta = Resolver.resolveInputParameterFromRequest(bodyObject, request, body, MAPPER);
        assertTrue(meta instanceof Map);
        assertEquals(1, ((Map<?, ?>) meta).get("rank"));
      }

      @Test
      public void resolveInputParameterFromRequestShouldSupportConstantAndBodyFallbacks()
          throws Exception {
        Request request = new Request(Method.GET, "https://example.com/items");
        JsonNode body = MAPPER.readTree("{\"x\":1}");

        InputParameterSpec constant = new InputParameterSpec();
        constant.setName("x");
        constant.setValue("fixed");
        constant.setIn("query");

        InputParameterSpec missingBodyPath = new InputParameterSpec();
        missingBodyPath.setName("missing");
        missingBodyPath.setIn("body");
        missingBodyPath.setValue("$.does.not.exist");

        InputParameterSpec rawBody = new InputParameterSpec();
        rawBody.setName("raw");
        rawBody.setIn("body");

        assertEquals("fixed", Resolver.resolveInputParameterFromRequest(constant, request, body, MAPPER));
        assertNull(Resolver.resolveInputParameterFromRequest(missingBodyPath, request, body, MAPPER));
        assertEquals(body, Resolver.resolveInputParameterFromRequest(rawBody, request, body, MAPPER));
      }

      @Test
      public void resolveInputParametersToRequestShouldApplyHeaderQueryAndTemplateResolution() {
        Request clientRequest = new Request(Method.GET, "https://api.example.com/items");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("requestId", "abc");
        parameters.put("q", "ice class");

        InputParameterSpec header = new InputParameterSpec();
        header.setName("X-Trace");
        header.setIn("header");
        header.setValue("trace-{{requestId}}");

        InputParameterSpec query = new InputParameterSpec();
        query.setName("search");
        query.setIn("query");
        query.setTemplate("{{q}}");

        InputParameterSpec constant = new InputParameterSpec();
        constant.setName("X-Mode");
        constant.setIn("header");
        constant.setValue("strict");

        Resolver.resolveInputParametersToRequest(clientRequest, List.of(header, query, constant),
            parameters);

        assertEquals("trace-abc", clientRequest.getHeaders().getFirstValue("X-Trace", true));
        assertEquals("strict", clientRequest.getHeaders().getFirstValue("X-Mode", true));
        assertTrue(clientRequest.getResourceRef().toString().contains("search=ice+class"));
        assertEquals("trace-abc", parameters.get("X-Trace"));
        assertEquals("ice class", parameters.get("search"));
      }

      @Test
      public void resolveOutputMappingsShouldHandleNullAndInvalidArrayRoots() throws Exception {
        JsonNode clientRoot = MAPPER.readTree("{\"data\":{\"id\":1}}\n");

        assertEquals(NullNode.instance, Resolver.resolveOutputMappings(null, clientRoot, MAPPER));

        OutputParameterSpec arraySpec = new OutputParameterSpec();
        arraySpec.setType("array");
        arraySpec.setMapping("$.data");

        JsonNode mapped = Resolver.resolveOutputMappings(arraySpec, clientRoot, MAPPER);
        assertTrue(mapped.isNull());
      }

      @Test
      public void resolveOutputMappingsShouldHandleArrayItemFallbackAndNullProperties()
          throws Exception {
        JsonNode clientRoot = MAPPER.readTree("""
            {
              "rows": [
              {"name":"A"},
              {"other":"B"}
              ]
            }
            """);

        OutputParameterSpec itemSpec = new OutputParameterSpec();
        itemSpec.setType("object");
        OutputParameterSpec name = new OutputParameterSpec();
        name.setName("name");
        name.setType("string");
        name.setMapping("$.name");
        itemSpec.getProperties().add(name);

        OutputParameterSpec rootArray = new OutputParameterSpec();
        rootArray.setType("array");
        rootArray.setMapping("$.rows");
        rootArray.setItems(itemSpec);

        JsonNode mapped = Resolver.resolveOutputMappings(rootArray, clientRoot, MAPPER);
        assertTrue(mapped.isArray());
        assertEquals("A", mapped.get(0).get("name").asText());
        assertTrue(mapped.get(1).get("name").isNull());

        OutputParameterSpec noItemMapping = new OutputParameterSpec();
        noItemMapping.setType("array");
        noItemMapping.setMapping("$.rows");
        OutputParameterSpec primitiveItem = new OutputParameterSpec();
        primitiveItem.setType("string");
        noItemMapping.setItems(primitiveItem);

        JsonNode passthrough = Resolver.resolveOutputMappings(noItemMapping, clientRoot, MAPPER);
        assertEquals("A", passthrough.get(0).get("name").asText());
      }

      @Test
      public void resolveOutputMappingsShouldHandleObjectValuesAndPrimitiveFallback() throws Exception {
        JsonNode root = MAPPER.readTree("""
            {
              "map": {
              "a": {"id": 10},
              "b": {"other": 20}
              },
              "value": "xyz"
            }
            """);

        OutputParameterSpec valuesSpec = new OutputParameterSpec();
        valuesSpec.setType("object");
        valuesSpec.setMapping("$.map");
        OutputParameterSpec valueItem = new OutputParameterSpec();
        valueItem.setType("number");
        valueItem.setMapping("$.id");
        valuesSpec.setValues(valueItem);

        JsonNode values = Resolver.resolveOutputMappings(valuesSpec, root, MAPPER);
        assertEquals(10, values.get("a").asInt());
        assertTrue(values.get("b").isNull());

        OutputParameterSpec primitive = new OutputParameterSpec();
        primitive.setType("string");
        primitive.setMapping("$.value");

        JsonNode primitiveValue = Resolver.resolveOutputMappings(primitive, root, MAPPER);
        assertEquals("xyz", primitiveValue.asText());
      }

    @Test
    public void resolveOutputMappingsShouldResolveMustacheTemplatesInValue() {
        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("string");
        spec.setValue("Hello, {{name}}!");

        Map<String, Object> params = Map.of("name", "Voyager");
        JsonNode result = Resolver.resolveOutputMappings(spec, null, MAPPER, params);
        assertEquals("Hello, Voyager!", result.asText());
    }

    @Test
    public void resolveOutputMappingsShouldReturnRawValueWhenNoParameters() {
        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("string");
        spec.setValue("static-text");

        JsonNode result = Resolver.resolveOutputMappings(spec, null, MAPPER, null);
        assertEquals("static-text", result.asText());
    }

    /**
     * Regression test for #213: array parameters in Mustache body templates must be
     * JSON-serialized, not converted via toString().
     *
     * <p>Before the fix, passing a List as a parameter value produced [CREW-001, CREW-003]
     * (no quotes), resulting in invalid JSON when substituted into a body template.</p>
     */
    @Test
    public void resolveMustacheTemplateShouldJsonSerializeArrayParameters() {
        String template = "{\"shipImo\": \"{{shipImo}}\", \"crewIds\": {{crewIds}}}";
        Map<String, Object> params = Map.of(
            "shipImo", "IMO-9321483",
            "crewIds", List.of("CREW-001", "CREW-003")
        );

        String result = Resolver.resolveMustacheTemplate(template, params);

        assertEquals("{\"shipImo\": \"IMO-9321483\", \"crewIds\": [\"CREW-001\",\"CREW-003\"]}", result);
    }

    /**
     * The value encoder transforms what a variable tag prints, never the values sections are
     * evaluated on: a section on a {@code Boolean} renders the same with or without an encoder.
     */
    @Test
    public void resolveMustacheTemplateWithEncoderShouldKeepBooleanSectionSemantics() {
        String template = "a=1{{#flag}}&b=2{{/flag}}";
        Map<String, Object> params = Map.of("flag", Boolean.FALSE);

        assertEquals(Resolver.resolveMustacheTemplate(template, params),
                Resolver.resolveMustacheTemplate(template, params, v -> "<" + v + ">"));
        assertEquals("v=<x>&n=<3>&b=<true>",
                Resolver.resolveMustacheTemplate("v={{s}}&n={{n}}&b={{b}}",
                        Map.of("s", "x", "n", 3, "b", true), v -> "<" + v + ">"));
    }

    /**
     * The value encoder applies to every value a template prints: nested paths, unescaped tags
     * and section items included, not only top-level parameters.
     */
    @Test
    public void resolveMustacheTemplateWithEncoderShouldTransformNestedAndUnescapedValues() {
        Map<String, Object> params = Map.of("step",
                Map.of("v", "x", "inner", Map.of("w", "y"), "items", List.of("p", "q")));

        assertEquals("a=<x>&b=<y>&c=<x>&d=<x>&<p><q>",
                Resolver.resolveMustacheTemplate(
                        "a={{step.v}}&b={{step.inner.w}}&c={{{step.v}}}&d={{&step.v}}"
                                + "&{{#step.items}}{{.}}{{/step.items}}",
                        params, v -> "<" + v + ">"));
    }

    @Test
    public void resolveMustacheTemplateWithoutEncoderShouldRenderNestedValuesUnchanged() {
        Map<String, Object> params = Map.of("step", Map.of("v", "x&y", "n", 2));

        assertEquals("a=x&y&n=2",
                Resolver.resolveMustacheTemplate("a={{step.v}}&n={{step.n}}", params));
        assertEquals("a=x&y&n=2",
                Resolver.resolveMustacheTemplate("a={{step.v}}&n={{step.n}}", params, null));
    }

    /**
     * Regression test for #213 (escapeHTML): Mustache HTML-escaping is disabled, so non-ASCII
     * characters must pass through unchanged.
     *
     * <p>Before the fix, escapeHTML was enabled by default, which would have corrupted characters
     * like ø (U+00F8) into their HTML entity equivalents.</p>
     */
    @Test
    public void resolveMustacheTemplateShouldPreserveNonAsciiCharacters() {
        String template = "{\"name\": \"{{name}}\", \"port\": \"{{port}}\"}";
        Map<String, Object> params = Map.of(
            "name", "Erik Lindstrøm",
            "port", "Göteborg"
        );

        String result = Resolver.resolveMustacheTemplate(template, params);

        assertEquals("{\"name\": \"Erik Lindstrøm\", \"port\": \"Göteborg\"}", result);
    }

    /**
     * A nested array with a {@code mapping} and object {@code items} must be shaped element by
     * element, exactly as a root-level array is — not passed through raw (#772).
     */
    @Test
    public void resolveOutputMappingsShouldShapeNestedMappedArrayItems() throws Exception {
        JsonNode apiResponse = MAPPER.readTree("""
                { "crew": [ { "full_name": "Ada", "rank": "captain" }, { "full_name": "Grace" } ] }
                """);
        OutputParameterSpec name = new OutputParameterSpec("name", "string", null, "$.full_name");
        OutputParameterSpec item = new OutputParameterSpec();
        item.setType("object");
        item.getProperties().add(name);
        OutputParameterSpec crew = new OutputParameterSpec("crew", "array", null, "$.crew");
        crew.setItems(item);
        OutputParameterSpec root = new OutputParameterSpec();
        root.setType("object");
        root.getProperties().add(crew);

        JsonNode result = Resolver.resolveOutputMappings(root, apiResponse, MAPPER);

        assertEquals(MAPPER.readTree("""
                { "crew": [ { "name": "Ada" }, { "name": "Grace" } ] }
                """), result);
    }

    /**
     * A nested object with both a {@code mapping} and {@code properties} must be assembled from its
     * properties, resolved relative to the extracted sub-node (#772).
     */
    @Test
    public void resolveOutputMappingsShouldShapeMappedNestedObjectRelativeToItsMapping()
            throws Exception {
        JsonNode apiResponse = MAPPER.readTree("""
                { "dimensions": { "length_overall": 229, "beam": 32 } }
                """);
        OutputParameterSpec length = new OutputParameterSpec("length", "number", null,
                "$.length_overall");
        OutputParameterSpec dimensions = new OutputParameterSpec("dimensions", "object", null,
                "$.dimensions");
        dimensions.getProperties().add(length);
        OutputParameterSpec root = new OutputParameterSpec();
        root.setType("object");
        root.getProperties().add(dimensions);

        JsonNode result = Resolver.resolveOutputMappings(root, apiResponse, MAPPER);

        assertEquals(MAPPER.readTree("""
                { "dimensions": { "length": 229 } }
                """), result);
    }

    @Test
    public void resolveOutputMappingsShouldEmitNullForMappedNestedObjectWhenSubNodeIsMissing()
            throws Exception {
        OutputParameterSpec length = new OutputParameterSpec("length", "number", null,
                "$.length_overall");
        OutputParameterSpec dimensions = new OutputParameterSpec("dimensions", "object", null,
                "$.dimensions");
        dimensions.getProperties().add(length);
        OutputParameterSpec root = new OutputParameterSpec();
        root.setType("object");
        root.getProperties().add(dimensions);

        JsonNode result = Resolver.resolveOutputMappings(root, MAPPER.readTree("{}"), MAPPER);

        assertTrue(result.has("dimensions"));
        assertTrue(result.get("dimensions").isNull());
    }

    @Test
    public void resolveOutputMappingsShouldShapeNestedValuesMap() throws Exception {
        JsonNode apiResponse = MAPPER.readTree("""
                { "ports": { "NOOSL": { "name": "Oslo", "country": "NO" } } }
                """);
        OutputParameterSpec ports = new OutputParameterSpec("ports", "object", null, "$.ports");
        ports.setValues(new OutputParameterSpec(null, "string", null, "$.name"));
        OutputParameterSpec root = new OutputParameterSpec();
        root.setType("object");
        root.getProperties().add(ports);

        JsonNode result = Resolver.resolveOutputMappings(root, apiResponse, MAPPER);

        assertEquals(MAPPER.readTree("""
                { "ports": { "NOOSL": "Oslo" } }
                """), result);
    }

    @Test
    public void resolveOutputMappingsShouldEmitNestedStaticValue() throws Exception {
        OutputParameterSpec source = new OutputParameterSpec("source", "string", null, null);
        source.setValue("registry:{{imo}}");
        OutputParameterSpec root = new OutputParameterSpec();
        root.setType("object");
        root.getProperties().add(source);

        JsonNode result = Resolver.resolveOutputMappings(root, MAPPER.readTree("{}"), MAPPER,
                Map.of("imo", "IMO-1"));

        assertEquals("registry:IMO-1", result.path("source").asText());
    }

    // ── Coercion to the declared type (exposed outputs, #772) ──────────────────────────────

    @Test
    public void coerceToDeclaredTypeShouldParseNumericText() throws Exception {
        JsonNode coerced = Resolver.coerceToDeclaredType(
                new OutputParameterSpec("n", "number", null, null), MAPPER.readTree("\"42.5\""),
                MAPPER);

        assertTrue(coerced.isNumber());
        assertEquals(42.5, coerced.asDouble());
    }

    @Test
    public void coerceToDeclaredTypeShouldNullNonNumericTextDeclaredAsNumber() throws Exception {
        JsonNode coerced = Resolver.coerceToDeclaredType(
                new OutputParameterSpec("n", "number", null, null), MAPPER.readTree("\"n/a\""),
                MAPPER);

        assertTrue(coerced.isNull());
    }

    @Test
    public void coerceToDeclaredTypeShouldParseBooleanTextCaseInsensitively() throws Exception {
        JsonNode coerced = Resolver.coerceToDeclaredType(
                new OutputParameterSpec("b", "boolean", null, null), MAPPER.readTree("\"TRUE\""),
                MAPPER);

        assertTrue(coerced.isBoolean());
        assertTrue(coerced.asBoolean());
    }

    @Test
    public void coerceToDeclaredTypeShouldRenderScalarsAndContainersAsStrings() throws Exception {
        OutputParameterSpec string = new OutputParameterSpec("s", "string", null, null);

        assertEquals("42", Resolver.coerceToDeclaredType(string, MAPPER.readTree("42"), MAPPER)
                .textValue());
        assertEquals("{\"a\":1}", Resolver.coerceToDeclaredType(string,
                MAPPER.readTree("{\"a\":1}"), MAPPER).textValue());
    }

    @Test
    public void coerceToDeclaredTypeShouldNullContainerTypeMismatch() throws Exception {
        assertTrue(Resolver.coerceToDeclaredType(
                new OutputParameterSpec("o", "object", null, null), MAPPER.readTree("[1]"), MAPPER)
                .isNull());
        assertTrue(Resolver.coerceToDeclaredType(
                new OutputParameterSpec("a", "array", null, null), MAPPER.readTree("{}"), MAPPER)
                .isNull());
    }

    @Test
    public void coerceToDeclaredTypeShouldKeepValueWhenNoTypeIsDeclared() throws Exception {
        JsonNode value = MAPPER.readTree("\"42\"");

        assertEquals(value, Resolver.coerceToDeclaredType(
                new OutputParameterSpec("x", null, null, null), value, MAPPER));
    }

    @Test
    public void resolveExposedOutputMappingsShouldCoerceNestedLeaves() throws Exception {
        JsonNode apiResponse = MAPPER.readTree("""
                { "tonnage": "42000", "active": "false", "crew": [ { "age": "41" } ] }
                """);
        OutputParameterSpec age = new OutputParameterSpec("age", "number", null, "$.age");
        OutputParameterSpec item = new OutputParameterSpec();
        item.setType("object");
        item.getProperties().add(age);
        OutputParameterSpec crew = new OutputParameterSpec("crew", "array", null, "$.crew");
        crew.setItems(item);
        OutputParameterSpec root = new OutputParameterSpec();
        root.setType("object");
        root.getProperties().addAll(List.of(
                new OutputParameterSpec("tonnage", "number", null, "$.tonnage"),
                new OutputParameterSpec("active", "boolean", null, "$.active"), crew));

        JsonNode result = Resolver.resolveExposedOutputMappings(root, apiResponse, MAPPER);

        assertEquals(MAPPER.readTree("""
                { "tonnage": 42000, "active": false, "crew": [ { "age": 41 } ] }
                """), result);
    }

    @Test
    public void resolveOutputMappingsShouldNotCoerceConsumedValues() throws Exception {
        JsonNode result = Resolver.resolveOutputMappings(
                new OutputParameterSpec("tonnage", "number", null, "$.tonnage"),
                MAPPER.readTree("{ \"tonnage\": \"42000\" }"), MAPPER);

        assertTrue(result.isTextual(),
                "consumed (step) outputs keep their upstream JSON type; only exposed outputs coerce");
    }

    // ── Steps-mode shaping (#772) ──────────────────────────────────────────────────────────

    @Test
    public void shapeStepOutputShouldNullFillUnresolvedDeclaredParameters() throws Exception {
        ObjectNode assembled = (ObjectNode) MAPPER.readTree("{ \"voyageId\": \"V-1\" }");

        JsonNode shaped = Resolver.shapeStepOutput(List.of(
                new OutputParameterSpec("voyageId", "string", null, null),
                new OutputParameterSpec("status", "string", null, null)), assembled, MAPPER);

        assertEquals(MAPPER.readTree("{ \"voyageId\": \"V-1\", \"status\": null }"), shaped);
    }

    @Test
    public void shapeStepOutputShouldShapeArrayItemsAndCoerceLeaves() throws Exception {
        ObjectNode assembled = (ObjectNode) MAPPER.readTree("""
                { "crew": [ { "fullName": "Ada", "age": "41" }, { "fullName": "Grace" }, "x" ] }
                """);
        OutputParameterSpec item = new OutputParameterSpec();
        item.setType("object");
        item.getProperties().addAll(List.of(
                new OutputParameterSpec("fullName", "string", null, null),
                new OutputParameterSpec("age", "number", null, null)));
        OutputParameterSpec crew = new OutputParameterSpec("crew", "array", null, null);
        crew.setItems(item);

        JsonNode shaped = Resolver.shapeStepOutput(List.of(crew), assembled, MAPPER);

        assertEquals(MAPPER.readTree("""
                { "crew": [ { "fullName": "Ada", "age": 41 },
                            { "fullName": "Grace", "age": null },
                            null ] }
                """), shaped);
    }

    @Test
    public void shapeStepOutputShouldNullFillNestedObjectProperties() throws Exception {
        ObjectNode assembled = (ObjectNode) MAPPER.readTree("{ \"route\": { \"from\": \"Oslo\" } }");
        OutputParameterSpec route = new OutputParameterSpec("route", "object", null, null);
        route.getProperties().addAll(List.of(
                new OutputParameterSpec("from", "string", null, null),
                new OutputParameterSpec("to", "string", null, null)));

        JsonNode shaped = Resolver.shapeStepOutput(List.of(route), assembled, MAPPER);

        assertEquals(MAPPER.readTree("{ \"route\": { \"from\": \"Oslo\", \"to\": null } }"),
                shaped);
    }

    @Test
    public void shapeStepOutputShouldKeepMappedKeysThatAreNotDeclared() throws Exception {
        ObjectNode assembled = (ObjectNode) MAPPER.readTree("{ \"a\": 1, \"extra\": true }");

        JsonNode shaped = Resolver.shapeStepOutput(
                List.of(new OutputParameterSpec("a", "number", null, null)), assembled, MAPPER);

        assertTrue(shaped.path("extra").asBoolean(),
                "shaping must never remove data a caller already receives");
    }
}
