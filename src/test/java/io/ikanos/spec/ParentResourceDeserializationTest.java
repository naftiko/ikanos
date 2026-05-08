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
package io.ikanos.spec;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that parentResource is properly populated by Jackson during deserialization.
 */
public class ParentResourceDeserializationTest {

    @Test
    public void testOperationParentResourcePopulatedFromHttpResourceSpec() throws Exception {
        String yaml = """
                type: http
                baseUri: https://api.example.com
                resources:
                  - path: /users
                    name: users
                    operations:
                      - method: GET
                        name: listUsers
                        label: List Users
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        HttpClientSpec spec = mapper.readValue(yaml, HttpClientSpec.class);

        // Verify the resource was parsed
        assertNotNull(spec.getResources(), "Resources should not be null");
        assertEquals(1, spec.getResources().size(), "Should have 1 resource");

        HttpClientResourceSpec resource = spec.getResources().get(0);
        assertEquals("/users", resource.getPath(), "Resource path should be /users");

        // Verify the operation was parsed
        assertNotNull(resource.getOperations(), "Operations should not be null");
        assertEquals(1, resource.getOperations().size(), "Should have 1 operation");

        OperationSpec operation = resource.getOperations().get(0);
        assertEquals("GET", operation.getMethod(), "Operation method should be GET");

        // THIS IS THE KEY TEST: parentResource should be populated
        assertNotNull(operation.getParentResource(), "parentResource should not be null");
        assertSame(resource, operation.getParentResource(), "parentResource should reference the HttpResourceSpec");
    }

    @Test
    public void testMultipleOperationsGetCorrectParentResource() throws Exception {
        String yaml = """
                type: http
                baseUri: https://api.example.com
                resources:
                  - path: /users
                    name: users
                    operations:
                      - method: GET
                        name: listUsers
                        label: List Users
                      - method: POST
                        name: createUser
                        label: Create User
                      - method: DELETE
                        name: deleteUser
                        label: Delete User
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        HttpClientSpec spec = mapper.readValue(yaml, HttpClientSpec.class);

        HttpClientResourceSpec resource = spec.getResources().get(0);
        assertEquals(3, resource.getOperations().size(), "Should have 3 operations");

        // Verify all operations have the correct parent resource
        for (OperationSpec operation : resource.getOperations()) {
            assertNotNull(operation.getParentResource(), "parentResource should not be null for operation: " + operation.getName());
            assertSame(resource, operation.getParentResource(), "parentResource should reference the correct HttpResourceSpec");
        }
    }

    @Test
    public void testMultipleResourcesEachOperationHasCorrectParent() throws Exception {
        String yaml = """
                type: http
                baseUri: https://api.example.com
                resources:
                  - path: /users
                    name: users
                    operations:
                      - method: GET
                        name: listUsers
                        label: List Users
                  - path: /products
                    name: products
                    operations:
                      - method: GET
                        name: listProducts
                        label: List Products
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        HttpClientSpec spec = mapper.readValue(yaml, HttpClientSpec.class);

        assertEquals(2, spec.getResources().size(), "Should have 2 resources");

        HttpClientResourceSpec usersResource = spec.getResources().get(0);
        assertEquals("/users", usersResource.getPath(), "First resource should be /users");
        assertEquals(1, usersResource.getOperations().size(), "Users resource should have 1 operation");

        OperationSpec listUsersOp = usersResource.getOperations().get(0);
        assertSame(usersResource, listUsersOp.getParentResource(), "listUsers operation should reference usersResource");

        HttpClientResourceSpec productsResource = spec.getResources().get(1);
        assertEquals("/products", productsResource.getPath(), "Second resource should be /products");
        assertEquals(1, productsResource.getOperations().size(), "Products resource should have 1 operation");

        OperationSpec listProductsOp = productsResource.getOperations().get(0);
        assertSame(productsResource, listProductsOp.getParentResource(), "listProducts operation should reference productsResource");
    }

}
