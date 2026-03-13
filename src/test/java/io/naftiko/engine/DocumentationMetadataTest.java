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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.naftiko.spec.DocumentationMetadata;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;
import io.naftiko.spec.exposes.RestServerOperationSpec;
import io.naftiko.spec.exposes.RestServerResourceSpec;
import io.naftiko.spec.exposes.RestServerStepSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.exposes.OperationStepCallSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DocumentationMetadata engine utility.
 * Validates extraction and formatting of specification documentation.
 */
public class DocumentationMetadataTest {

    private RestServerResourceSpec resource;
    private RestServerOperationSpec operation;

    @BeforeEach
    public void setUp() {
        resource = new RestServerResourceSpec("/users", null, null, "User management endpoints", null);
        
        operation = new RestServerOperationSpec(resource, "GET", "getUser", "Get User");
        operation.setDescription("Retrieves a user by ID");
    }

    @Test
    public void testExtractResourceDocumentation() {
        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);
        
        assertNotNull(docs);
        assertEquals("/users", docs.get("path"));
        assertEquals("User management endpoints", docs.get("description"));
        assertNotNull(docs.get("operations"));
    }

    @Test
    public void testExtractResourceDocumentationWithMultipleOperations() {
        RestServerOperationSpec op1 = new RestServerOperationSpec(resource, "GET", "list", "List Users");
        op1.setDescription("List all users");
        resource.getOperations().add(op1);
        
        RestServerOperationSpec op2 = new RestServerOperationSpec(resource, "POST", "create", "Create User");
        op2.setDescription("Create a new user");
        resource.getOperations().add(op2);
        
        Map<String, Object> docs = DocumentationMetadata.extractResourceDocumentation(resource);
        
        @SuppressWarnings("unchecked")
        Map<String, String> operations = (Map<String, String>) docs.get("operations");
        assertEquals(2, operations.size());
        assertEquals("List all users", operations.get("list"));
        assertEquals("Create a new user", operations.get("create"));
    }

    @Test
    public void testExtractParameterDocumentation() {
        List<InputParameterSpec> inputs = new ArrayList<>();
        InputParameterSpec inputParam = new InputParameterSpec("userId", "string", "query", null);
        inputParam.setDescription("The user ID");
        inputs.add(inputParam);
        
        List<OutputParameterSpec> outputs = new ArrayList<>();
        OutputParameterSpec outputParam = new OutputParameterSpec("user", "object", "body", null);
        outputParam.setDescription("The user object");
        outputs.add(outputParam);
        
        Map<String, Object> docs = DocumentationMetadata.extractParameterDocumentation(inputs, outputs);
        
        assertNotNull(docs);
        @SuppressWarnings("unchecked")
        Map<String, String> inputDocs = (Map<String, String>) docs.get("inputs");
        @SuppressWarnings("unchecked")
        Map<String, String> outputDocs = (Map<String, String>) docs.get("outputs");
        
        assertEquals(1, inputDocs.size());
        assertTrue(inputDocs.get("userId").contains("The user ID"));
        assertTrue(inputDocs.get("userId").contains("string"));
        
        assertEquals(1, outputDocs.size());
        assertTrue(outputDocs.get("user").contains("The user object"));
        assertTrue(outputDocs.get("user").contains("object"));
    }

    @Test
    public void testExtractStepDocumentation() {
        List<RestServerStepSpec> steps = new ArrayList<>();
        
        ServerCallSpec call1 = new ServerCallSpec("users.get", null, "Fetch user details");
        RestServerStepSpec step1 = new RestServerStepSpec(call1, null, "Retrieve user information");
        steps.add(step1);
        
        ServerCallSpec call2 = new ServerCallSpec("users.audit", null);
        RestServerStepSpec step2 = new RestServerStepSpec(call2, null, "Log the access");
        steps.add(step2);
        
        List<Map<String, Object>> stepDocs = DocumentationMetadata.extractStepDocumentation(steps);
        
        assertEquals(2, stepDocs.size());
        
        // Step 1
        assertEquals(0, stepDocs.get(0).get("index"));
        assertEquals("Retrieve user information", stepDocs.get(0).get("description"));
        assertEquals("users.get", stepDocs.get(0).get("operation"));
        assertEquals("Fetch user details", stepDocs.get(0).get("callDescription"));
        
        // Step 2
        assertEquals(1, stepDocs.get(1).get("index"));
        assertEquals("Log the access", stepDocs.get(1).get("description"));
        assertEquals("users.audit", stepDocs.get(1).get("operation"));
    }

    @Test
    public void testFormatOperationDocumentation() {
        OperationStepCallSpec step = new OperationStepCallSpec("Fetch user from database", "db.fetchUser");
        operation.getSteps().add(step);
        
        String doc = DocumentationMetadata.formatOperationDocumentation(resource, operation);
        
        assertNotNull(doc);
        assertTrue(doc.contains("Resource: /users"));
        assertTrue(doc.contains("User management endpoints"));
        assertTrue(doc.contains("Operation: getUser"));
        assertTrue(doc.contains("Retrieves a user by ID"));
        assertTrue(doc.contains("Steps:"));
        assertTrue(doc.contains("Fetch user from database"));
    }

    @Test
    public void testRestServerStepSpecWithDescription() {
        ServerCallSpec call = new ServerCallSpec("getUser");
        RestServerStepSpec step = new RestServerStepSpec(call, null, "Fetch user by ID");
        
        assertEquals("Fetch user by ID", step.getDescription());
    }

    @Test
    public void testOperationStepCallSpecWithName() {
        OperationStepCallSpec step = new OperationStepCallSpec("Fetch user by ID", "getUser");
        
        assertEquals("Fetch user by ID", step.getName());
        assertEquals("getUser", step.getCall());
    }

    @Test
    public void testRestServerCallSpecWithDescription() {
        ServerCallSpec call = new ServerCallSpec("users.get", new HashMap<>(), "Retrieve a user");
        
        assertEquals("Retrieve a user", call.getDescription());
    }

    @Test
    public void testExtractParameterDocumentationWithNullLists() {
        Map<String, Object> docs = DocumentationMetadata.extractParameterDocumentation(null, null);
        
        assertNotNull(docs);
        @SuppressWarnings("unchecked")
        Map<String, String> inputs = (Map<String, String>) docs.get("inputs");
        @SuppressWarnings("unchecked")
        Map<String, String> outputs = (Map<String, String>) docs.get("outputs");
        
        assertEquals(0, inputs.size());
        assertEquals(0, outputs.size());
    }

    @Test
    public void testFormatOperationDocumentationWithoutSteps() {
        String doc = DocumentationMetadata.formatOperationDocumentation(resource, operation);
        
        assertNotNull(doc);
        assertTrue(doc.contains("Resource: /users"));
        assertTrue(doc.contains("Operation: getUser"));
    }

}
