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
package io.ikanos.spec.consumes.http.tunnel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.ikanos.spec.util.VersionHelper;

import java.io.InputStream;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates the optional {@code tunnel} property on {@code ConsumesHttp} and the
 * {@code TunnelConfig} / {@code TunnelZiti} definitions added to
 * {@code ikanos-schema.json}.
 *
 * <p>See blueprint {@code reverse-tunnel-private-network.md} Phase 1.</p>
 */
class TunnelSchemaValidationTest {

    private static JsonSchema schema;
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = TunnelSchemaValidationTest.class
                .getClassLoader()
                .getResourceAsStream("schemas/ikanos-schema.json")) {
            assertNotNull(in, "schemas/ikanos-schema.json must be on the test classpath");
            JsonNode schemaNode = JSON.readTree(in);
            JsonSchemaFactory factory =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            schema = factory.getSchema(schemaNode);
        }
    }

    private Set<ValidationMessage> validateYaml(String yaml) throws Exception {
        JsonNode data = YAML.readTree(yaml);
        return schema.validate(data);
    }

    private Set<ValidationMessage> validateClasspathYaml(String resource) throws Exception {
        try (InputStream in = TunnelSchemaValidationTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the test classpath");
            JsonNode data = YAML.readTree(in);
            return schema.validate(data);
        }
    }

    // ----- happy paths -----

    @Test
    @DisplayName("schema accepts a ConsumesHttp adapter with a full ziti tunnel block")
    void schemaShouldAcceptConsumesHttpWithZitiTunnel() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            binds:
              - namespace: "secrets"
                description: "Secret bindings."
                location: "file:///./shared/secrets.yaml"
                keys:
                  ZITI_IDENTITY: "ziti-identity-path"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "ziti"
                    service: "crm-api"
                    identity: "{{secrets.ZITI_IDENTITY}}"
                    fallback: "fail"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts a ConsumesHttp adapter when tunnel omits the optional fallback")
    void schemaShouldAcceptZitiTunnelWithoutFallback() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "ziti"
                    service: "crm-api"
                    identity: "/etc/ziti/id.json"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts a ConsumesHttp adapter with no tunnel at all (backward compatible)")
    void schemaShouldAcceptConsumesHttpWithoutTunnel() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.example.com"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("bundled example reverse-tunnel-ziti.yml passes schema validation")
    void schemaShouldAcceptBundledReverseTunnelExample() throws Exception {
        Set<ValidationMessage> errors =
            validateClasspathYaml("schemas/examples/reverse-tunnel-ziti.yml");
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    // ----- negative paths -----

    @Test
    @DisplayName("schema rejects an unknown tunnel.type value")
    void schemaShouldRejectUnknownTunnelType() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "wireguard"
                    service: "crm-api"
                    identity: "/etc/wg/id.conf"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected validation to fail for unknown tunnel.type, but it passed.");
    }

    @Test
    @DisplayName("schema rejects an invalid fallback enum value")
    void schemaShouldRejectInvalidFallbackEnum() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "ziti"
                    service: "crm-api"
                    identity: "/etc/ziti/id.json"
                    fallback: "retry"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected validation to fail for invalid fallback enum, but it passed.");
    }

    @Test
    @DisplayName("schema rejects a ziti tunnel that is missing the required service field")
    void schemaShouldRejectZitiTunnelWithoutService() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "ziti"
                    identity: "/etc/ziti/id.json"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected validation to fail when tunnel.service is missing, but it passed.");
    }

    @Test
    @DisplayName("schema rejects a ziti tunnel that is missing the required identity field")
    void schemaShouldRejectZitiTunnelWithoutIdentity() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "ziti"
                    service: "crm-api"
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected validation to fail when tunnel.identity is missing, but it passed.");
    }

    @Test
    @DisplayName("schema rejects unknown extra properties inside a ziti tunnel block")
    void schemaShouldRejectUnknownTunnelProperties() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "crm"
                  baseUri: "https://crm.internal"
                  tunnel:
                    type: "ziti"
                    service: "crm-api"
                    identity: "/etc/ziti/id.json"
                    timeoutSeconds: 30
                  resources:
                    customers:
                      path: "/customers"
                      operations:
                        list-customers:
                          method: "GET"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected validation to fail for unknown property under tunnel, but it passed.");
    }

}
