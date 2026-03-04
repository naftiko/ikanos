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
package io.naftiko.spec.exposes;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for OperationStep deserialization.
 * Validates polymorphic deserialization of call and lookup steps from YAML.
 */
public class OperationStepDeserializationTest {

    @Test
    public void testDeserializeCallStep() throws Exception {
        String yaml = """
                type: call
                name: fetch-database
                call: notion.get-database
                with:
                  database_id: "$this.sample.database_id"
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertNotNull(step, "Step should not be null");
        assertInstanceOf(OperationStepCallSpec.class, step, "Step should be OperationStepCallSpec");

        OperationStepCallSpec callStep = (OperationStepCallSpec) step;
        assertEquals("call", callStep.getType(), "Type should be 'call'");
        assertEquals("fetch-database", callStep.getName(), "Name mismatch");
        assertEquals("notion.get-database", callStep.getCall(), "Call reference mismatch");
        assertNotNull(callStep.getWith(), "With parameters should not be null");
        assertTrue(callStep.getWith().containsKey("database_id"), "With should contain database_id");
    }

    @Test
    public void testDeserializeCallStepWithoutWith() throws Exception {
        String yaml = """
                type: call
                name: list-items
                call: api.list-items
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepCallSpec.class, step);
        OperationStepCallSpec callStep = (OperationStepCallSpec) step;
        assertEquals("list-items", callStep.getName());
        assertEquals("api.list-items", callStep.getCall());
        assertNull(callStep.getWith(), "With should be null when not specified");
    }

    @Test
    public void testDeserializeLookupStep() throws Exception {
        String yaml = """
                type: lookup
                name: find-user
                index: list-users
                match: email
                lookupValue: "$this.sample.user_email"
                outputParameters:
                  - login
                  - id
                  - fullName
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertNotNull(step, "Step should not be null");
        assertInstanceOf(OperationStepLookupSpec.class, step, "Step should be OperationStepLookupSpec");

        OperationStepLookupSpec lookupStep = (OperationStepLookupSpec) step;
        assertEquals("lookup", lookupStep.getType(), "Type should be 'lookup'");
        assertEquals("find-user", lookupStep.getName(), "Name mismatch");
        assertEquals("list-users", lookupStep.getIndex(), "Index mismatch");
        assertEquals("email", lookupStep.getMatch(), "Match field mismatch");
        assertEquals("$this.sample.user_email", lookupStep.getLookupValue(), "Lookup value mismatch");
        assertEquals(3, lookupStep.getOutputParameters().size(), "Should have 3 output parameters");
        assertTrue(lookupStep.getOutputParameters().contains("login"), "Should contain 'login'");
        assertTrue(lookupStep.getOutputParameters().contains("id"), "Should contain 'id'");
        assertTrue(lookupStep.getOutputParameters().contains("fullName"), "Should contain 'fullName'");
    }

    @Test
    public void testDeserializeLookupStepSingleOutputParameter() throws Exception {
        String yaml = """
                type: lookup
                name: find-role
                index: roles
                match: id
                lookupValue: "$this.sample.role_id"
                outputParameters:
                  - role_name
                """;

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        OperationStepSpec step = yamlMapper.readValue(yaml, OperationStepSpec.class);

        assertInstanceOf(OperationStepLookupSpec.class, step);
        OperationStepLookupSpec lookupStep = (OperationStepLookupSpec) step;
        assertEquals(1, lookupStep.getOutputParameters().size());
        assertEquals("role_name", lookupStep.getOutputParameters().get(0));
    }

}
