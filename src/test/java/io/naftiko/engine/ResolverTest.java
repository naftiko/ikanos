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
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.InputParameterSpec;

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
}
