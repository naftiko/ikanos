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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CapabilityBootstrapTest {

    private String schemaVersion;

    @BeforeEach
    public void setUp() {
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void constructorShouldFailWhenNoExposesDefined() throws Exception {
        IkanosSpec spec = parseYaml("""
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
        IkanosSpec spec = parseYaml("""
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
    public void constructorShouldResolveRuntimeBindings() throws Exception {
        IkanosSpec spec = parseYaml("""
                ikanos: "%s"
                binds:
                  - namespace: "env"
                    keys:
                      env_path: PATH
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

        Object value = capability.getBindings().get("env_path");
        assertTrue(value != null && !String.valueOf(value).isBlank());
    }

    private static IkanosSpec parseYaml(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(yaml, IkanosSpec.class);
    }
}