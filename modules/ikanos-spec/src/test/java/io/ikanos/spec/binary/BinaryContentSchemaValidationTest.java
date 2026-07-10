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
package io.ikanos.spec.binary;

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
 * Validates the additive binary-content schema fields introduced by Phase 0 of the
 * {@code capability-binary-content} blueprint:
 *
 * <ul>
 *   <li>{@code outputRawFormat: binary} + {@code outputMediaType} + {@code maxBinarySize}
 *       on a consumed HTTP operation (§5);</li>
 *   <li>{@code outputMediaType} on an aggregate flow (§6);</li>
 *   <li>{@code responses} contract and {@code responseBinary} shorthand on a REST operation (§7);</li>
 *   <li>adapter-level {@code maxBinarySize} on an MCP server and {@code binary: true} on a
 *       dynamic MCP resource (§8).</li>
 * </ul>
 *
 * <p>Phase 0 is purely additive: the engine does not yet read or buffer bytes.</p>
 */
class BinaryContentSchemaValidationTest {

    private static JsonSchema schema;
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = BinaryContentSchemaValidationTest.class
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
        try (InputStream in = BinaryContentSchemaValidationTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the test classpath");
            JsonNode data = YAML.readTree(in);
            return schema.validate(data);
        }
    }

    // ----- consumes (§5) -----

    @Test
    @DisplayName("schema accepts outputRawFormat: binary with outputMediaType and maxBinarySize")
    void schemaShouldAcceptBinaryConsumedOperation() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
                          outputMediaType: "image/jpeg"
                          maxBinarySize: "5MiB"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema rejects a maxBinarySize that does not match the size pattern")
    void schemaShouldRejectMalformedMaxBinarySize() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
                          maxBinarySize: "5 megabytes"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(), "Expected a validation error for a malformed maxBinarySize");
    }

    @Test
    @DisplayName("schema rejects a maxBinarySize with no unit (unit is mandatory)")
    void schemaShouldRejectMaxBinarySizeWithoutUnit() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
                          maxBinarySize: "5"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected a validation error for a unit-less maxBinarySize ('5')");
    }

    @Test
    @DisplayName("schema accepts outputMediaType on a non-binary (default JSON) consumed operation")
    void schemaShouldAcceptOutputMediaTypeOnNonBinaryConsumedOperation() throws Exception {
        // §4.3.2: outputMediaType is orthogonal to outputRawFormat. A non-binary op
        // (default JSON parsing) may still advertise a vendor media type variant.
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "github"
                  baseUri: "https://api.github.com"
                  resources:
                    repos:
                      path: "/repos/{owner}/{repo}"
                      operations:
                        get-repo:
                          method: "GET"
                          outputMediaType: "application/vnd.github.v3+json"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    // ----- aggregates (§6) -----

    @Test
    @DisplayName("schema accepts outputMediaType on an aggregate flow")
    void schemaShouldAcceptOutputMediaTypeOnAggregateFlow() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
              aggregates:
                - display: "Photos"
                  namespace: "photos"
                  flows:
                    get-photo:
                      description: "Download a photo by id."
                      outputMediaType: "image/jpeg"
                      call: "photo-library.download-photo"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    // ----- exposes rest (§7) -----

    @Test
    @DisplayName("schema accepts a REST operation with a binary responses contract")
    void schemaShouldAcceptRestResponsesBinaryContract() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
              exposes:
                - type: "rest"
                  port: 8080
                  namespace: "photos-api"
                  resources:
                    photo-image:
                      path: "/photos/{{id}}/image"
                      operations:
                        get-photo-image:
                          method: "GET"
                          call: "photo-library.download-photo"
                          responses:
                            "200":
                              description: "Original image bytes."
                              content:
                                image/jpeg:
                                  binary: true
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema accepts a REST operation with the responseBinary shorthand")
    void schemaShouldAcceptRestResponseBinaryShorthand() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
              exposes:
                - type: "rest"
                  port: 8080
                  namespace: "photos-api"
                  resources:
                    photo-image:
                      path: "/photos/{{id}}/image"
                      operations:
                        get-photo-image:
                          method: "GET"
                          call: "photo-library.download-photo"
                          responseBinary:
                            status: 200
                            mediaType: "image/jpeg"
                            description: "Original image bytes."
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    @Test
    @DisplayName("schema rejects a REST operation declaring both responses and responseBinary")
    void schemaShouldRejectBothResponsesAndResponseBinary() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
              exposes:
                - type: "rest"
                  port: 8080
                  namespace: "photos-api"
                  resources:
                    photo-image:
                      path: "/photos/{{id}}/image"
                      operations:
                        get-photo-image:
                          method: "GET"
                          call: "photo-library.download-photo"
                          responseBinary:
                            mediaType: "image/jpeg"
                          responses:
                            "200":
                              content:
                                image/jpeg:
                                  binary: true
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertFalse(errors.isEmpty(),
            "Expected a validation error when both responses and responseBinary are declared");
    }

    // ----- exposes mcp (§8) -----

    @Test
    @DisplayName("schema accepts MCP adapter maxBinarySize and a binary dynamic resource")
    void schemaShouldAcceptMcpMaxBinarySizeAndBinaryResource() throws Exception {
        String yaml = """
            ikanos: "%s"
            info:
              display: "demo"
              description: "demo"
            capability:
              consumes:
                - type: "http"
                  namespace: "photo-library"
                  baseUri: "https://photos.example.org"
                  resources:
                    photos:
                      path: "/photos/{id}/binary"
                      operations:
                        download-photo:
                          method: "GET"
                          outputRawFormat: "binary"
              exposes:
                - type: "mcp"
                  port: 8765
                  namespace: "photos-mcp"
                  maxBinarySize: "25MiB"
                  tools:
                    get-photo:
                      description: "Download a photo by id."
                      call: "photo-library.download-photo"
                  resources:
                    photo-bytes:
                      display: "Photo bytes"
                      uri: "photos://library/{id}/bytes"
                      description: "Original-resolution bytes of a photo."
                      mimeType: "image/jpeg"
                      binary: true
                      call: "photo-library.download-photo"
            """.formatted(IKANOS);
        Set<ValidationMessage> errors = validateYaml(yaml);
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }

    // ----- bundled example -----

    @Test
    @DisplayName("bundled example capability-binary-content.yml passes schema validation")
    void schemaShouldAcceptBundledBinaryContentExample() throws Exception {
        Set<ValidationMessage> errors =
            validateClasspathYaml("schemas/examples/capability-binary-content.yml");
        assertTrue(errors.isEmpty(), "Expected no validation errors, but got: " + errors);
    }
}
