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
package io.ikanos;

import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.utils.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CapabilityBootstrapTest {

    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void constructorShouldFailWhenNoExposesDefined() throws Exception {
        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                capability:
                  exposes: []
                  consumes: []
                """.formatted(schemaVersion));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Capability(spec));
        assertEquals("Capability must expose at least one endpoint.", error.getMessage());
    }

    @Test
    public void constructorShouldCreateServerAndClientAdapters() throws Exception {
        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "orders-api"
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                  consumes:
                    - type: "http"
                      namespace: "orders-client"
                      baseUri: "http://localhost:8080"
                      resources:
                        - path: "/orders"
                          name: "orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                """.formatted(schemaVersion));

        Capability capability = new Capability(spec);

        assertEquals(1, capability.getServerAdapters().size());
        assertEquals(1, capability.getClientAdapters().size());
    }
    
    @Test
    void gettingBindingsShouldReturnResolvedValues() throws Exception {
        // Given
        IkanosSpec spec = TestUtils.parseYaml("""
                ikanos: "%s"
                binds:
                  - namespace: "env"
                    keys:
                      path: PATH
                  - namespace: "env2"
                    description: "Environment 2"
                    location: "file:///./src/test/resources/tutorial/shared/secrets.yaml"
                    keys:
                      registry_token: registry-bearer-token
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "orders-api"
                      resources:
                        - path: "/orders"
                          operations:
                            - method: "GET"
                              name: "list-orders"
                  consumes: []
                """.formatted(schemaVersion));
        Capability capability = new Capability(spec);
        
        // When
        Object path = capability.getBindings().get("path");
        Object registryToken = capability.getBindings().get("registry_token");
        Object unknown = capability.getBindings().get("unknown");

        // Then
        assertThat(path).isNotNull();
        assertThat(registryToken).isEqualTo("dummy-token");
        assertThat(unknown).isNull();
    }
}
