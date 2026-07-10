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
package io.ikanos.spec.util;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Test suite for OperationStep round-trip serialization/deserialization.
 * Validates that data is not lost when reading from YAML and writing back to JSON.
 */
public class OperationStepRoundTripTest {

    @Test
    public void testCallStepRoundTrip() throws Exception {
        String yaml = """
                type: call
                name: fetch-database
                call: notion.get-database
                with:
                  database_id: "$this.sample.database_id"
                  page_size: 100
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        // Deserialize from YAML
        OperationStepSpec original = yamlMapper.readValue(yaml, OperationStepSpec.class);

        // Serialize to JSON
        String jsonSerialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);

        // Deserialize back from JSON
        OperationStepSpec roundTrip = jsonMapper.readValue(jsonSerialized, OperationStepSpec.class);

        assertInstanceOf(OperationStepCallSpec.class, roundTrip);
        OperationStepCallSpec callStep = (OperationStepCallSpec) roundTrip;

        assertEquals("call", callStep.getType(), "Type mismatch");
        assertEquals("fetch-database", callStep.getName(), "Name mismatch");
        assertEquals("notion.get-database", callStep.getCall(), "Call reference mismatch");
        assertNotNull(callStep.getWith(), "With should not be null");
        assertEquals(2, callStep.getWith().size(), "With should have 2 entries");
        assertEquals("$this.sample.database_id", callStep.getWith().get("database_id"));
        assertEquals(100, callStep.getWith().get("page_size"));
    }

    @Test
    public void testLookupStepRoundTrip() throws Exception {
        String yaml = """
                type: lookup
                name: find-user
                index: list-users
                match: email
                lookupValue: "$this.sample.user_email"
                outputParameters:
                  - login
                  - id
                  - department
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        // Deserialize from YAML
        OperationStepSpec original = yamlMapper.readValue(yaml, OperationStepSpec.class);

        // Serialize to JSON
        String jsonSerialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);

        // Deserialize back from JSON
        OperationStepSpec roundTrip = jsonMapper.readValue(jsonSerialized, OperationStepSpec.class);

        assertInstanceOf(OperationStepLookupSpec.class, roundTrip);
        OperationStepLookupSpec lookupStep = (OperationStepLookupSpec) roundTrip;

        assertEquals("lookup", lookupStep.getType(), "Type mismatch");
        assertEquals("find-user", lookupStep.getName(), "Name mismatch");
        assertEquals("list-users", lookupStep.getIndex(), "Index mismatch");
        assertEquals("email", lookupStep.getMatch(), "Match field mismatch");
        assertEquals("$this.sample.user_email", lookupStep.getLookupValue(), "Lookup value mismatch");
        assertEquals(3, lookupStep.getOutputParameters().size(), "Should have 3 output parameters");
        assertTrue(lookupStep.getOutputParameters().contains("login"));
        assertTrue(lookupStep.getOutputParameters().contains("id"));
        assertTrue(lookupStep.getOutputParameters().contains("department"));
    }

    @Test
    public void testCallStepWithoutWithRoundTrip() throws Exception {
        String yaml = """
                type: call
                name: get-status
                call: api.get-status
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonMapper = new ObjectMapper();

        OperationStepSpec original = yamlMapper.readValue(yaml, OperationStepSpec.class);
        String jsonSerialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(original);
        OperationStepSpec roundTrip = jsonMapper.readValue(jsonSerialized, OperationStepSpec.class);

        assertInstanceOf(OperationStepCallSpec.class, roundTrip);
        OperationStepCallSpec callStep = (OperationStepCallSpec) roundTrip;

        assertEquals("get-status", callStep.getName());
        assertEquals("api.get-status", callStep.getCall());
        assertNull(callStep.getWith());
    }

    @Test
    public void testProgrammaticCallStepCreation() throws Exception {
        Map<String, Object> withMap = new HashMap<>();
        withMap.put("user_id", "$this.sample.user_id");
        withMap.put("include_details", true);

        OperationStepCallSpec callStep = new OperationStepCallSpec("fetch-user", "api.get-user", withMap);

        ObjectMapper jsonMapper = new ObjectMapper();

        // Serialize to JSON
        String jsonSerialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(callStep);

        // Deserialize back
        OperationStepSpec roundTrip = jsonMapper.readValue(jsonSerialized, OperationStepSpec.class);

        assertInstanceOf(OperationStepCallSpec.class, roundTrip);
        OperationStepCallSpec restored = (OperationStepCallSpec) roundTrip;

        assertEquals("call", restored.getType());
        assertEquals("fetch-user", restored.getName());
        assertEquals("api.get-user", restored.getCall());
        assertEquals(2, restored.getWith().size());
    }

    @Test
    public void testProgrammaticLookupStepCreation() throws Exception {
        java.util.List<String> outputParams = new java.util.ArrayList<>();
        outputParams.add("name");
        outputParams.add("email");

        OperationStepLookupSpec lookupStep = new OperationStepLookupSpec(
                "resolve-user",
                "users",
                "id",
                "$.user_id",
                outputParams);

        ObjectMapper jsonMapper = new ObjectMapper();

        // Serialize to JSON
        String jsonSerialized = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(lookupStep);

        // Deserialize back
        OperationStepSpec roundTrip = jsonMapper.readValue(jsonSerialized, OperationStepSpec.class);

        assertInstanceOf(OperationStepLookupSpec.class, roundTrip);
        OperationStepLookupSpec restored = (OperationStepLookupSpec) roundTrip;

        assertEquals("lookup", restored.getType());
        assertEquals("resolve-user", restored.getName());
        assertEquals("users", restored.getIndex());
        assertEquals("id", restored.getMatch());
        assertEquals("$.user_id", restored.getLookupValue());
        assertEquals(2, restored.getOutputParameters().size());
    }

}
