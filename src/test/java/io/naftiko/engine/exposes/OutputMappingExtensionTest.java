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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.naftiko.engine.Resolver;
import io.naftiko.spec.OutputParameterSpec;

public class OutputMappingExtensionTest {

    private ObjectMapper mapper = new ObjectMapper();

    private JsonNode invokeBuild(OutputParameterSpec spec, JsonNode root) throws Exception {
        return Resolver.resolveOutputMappings(spec, root, mapper);
    }

    @Test
    public void testConstTakesPrecedence() throws Exception {
        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("string");
        spec.setValue("CONST_VAL");

        JsonNode result = invokeBuild(spec, mapper.readTree("{}"));
        assertTrue(result.isTextual());
        assertEquals("CONST_VAL", result.asText());
    }

    @Test
    public void testArrayItemsObjectMapping() throws Exception {
        // Build the sample JSON programmatically to avoid escaping issues
        ObjectNode root = mapper.createObjectNode();
        ArrayNode results = mapper.createArrayNode();

        ObjectNode item1 = mapper.createObjectNode();
        ObjectNode props1 = mapper.createObjectNode();
        ObjectNode name1 = mapper.createObjectNode();
        ArrayNode title1 = mapper.createArrayNode();
        ObjectNode titleText1 = mapper.createObjectNode();
        titleText1.set("text", mapper.createObjectNode().put("content", "Alice"));
        title1.add(titleText1);
        name1.set("title", title1);
        props1.set("Name", name1);
        props1.set("Company",
                mapper.createObjectNode().set("rich_text",
                        mapper.createArrayNode().add(mapper.createObjectNode().set("text",
                                mapper.createObjectNode().put("content", "Acme")))));
        item1.set("properties", props1);

        ObjectNode item2 = mapper.createObjectNode();
        ObjectNode props2 = mapper.createObjectNode();
        ObjectNode name2 = mapper.createObjectNode();
        ArrayNode title2 = mapper.createArrayNode();
        ObjectNode titleText2 = mapper.createObjectNode();
        titleText2.set("text", mapper.createObjectNode().put("content", "Bob"));
        title2.add(titleText2);
        name2.set("title", title2);
        props2.set("Name", name2);
        props2.set("Company",
                mapper.createObjectNode().set("rich_text",
                        mapper.createArrayNode().add(mapper.createObjectNode().set("text",
                                mapper.createObjectNode().put("content", "BetaCo")))));
        item2.set("properties", props2);

        results.add(item1);
        results.add(item2);
        root.set("results", results);

        OutputParameterSpec rootSpec = new OutputParameterSpec();
        rootSpec.setType("array");
        rootSpec.setMapping("$.results");

        OutputParameterSpec items = new OutputParameterSpec();
        items.setType("object");

        OutputParameterSpec nameProp = new OutputParameterSpec("name", "string", "body",
                "$.properties.Name.title[0].text.content");
        OutputParameterSpec companyProp = new OutputParameterSpec("company", "string", "body",
                "$.properties.Company.rich_text[0].text.content");
        items.getProperties().add(nameProp);
        items.getProperties().add(companyProp);

        rootSpec.setItems(items);

        JsonNode mapped = invokeBuild(rootSpec, root);
        assertTrue(mapped.isArray());
        assertEquals(2, mapped.size());
        assertEquals("Alice", mapped.get(0).get("name").asText());
        assertEquals("Acme", mapped.get(0).get("company").asText());
        assertEquals("Bob", mapped.get(1).get("name").asText());
        assertEquals("BetaCo", mapped.get(1).get("company").asText());
    }

    @Test
    public void testValuesMapMapping() throws Exception {
        String json = "{\"data\":{\"a\":{\"id\":1,\"v\":\"x\"},\"b\":{\"id\":2}}}";
        JsonNode root = mapper.readTree(json);

        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("object");
        spec.setMapping("$.data");

        OutputParameterSpec values = new OutputParameterSpec();
        values.setType("integer");
        values.setMapping("$.id");
        spec.setValues(values);

        JsonNode mapped = invokeBuild(spec, root);
        assertTrue(mapped.isObject());
        assertEquals(1, mapped.get("a").asInt());
        assertEquals(2, mapped.get("b").asInt());
    }

    @Test
    public void testMaxLengthTruncation() throws Exception {
        String json = "{\"long\":\"abcdefghijkl\"}";
        JsonNode root = mapper.readTree(json);

        OutputParameterSpec spec = new OutputParameterSpec();
        spec.setType("string");
        spec.setMapping("$.long");
        spec.setMaxLength("5");

        JsonNode mapped = invokeBuild(spec, root);
        assertTrue(mapped.isTextual());
        assertEquals("abcde", mapped.asText());
    }
}
