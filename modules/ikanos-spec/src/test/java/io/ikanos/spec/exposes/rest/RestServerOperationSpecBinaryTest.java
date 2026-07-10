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
package io.ikanos.spec.exposes.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for the REST binary response contract on {@link RestServerOperationSpec}
 * ({@code responses} / {@code responseBinary}) — Phase 2 of the binary-content blueprint
 * (capability-binary-content.md §7).
 */
public class RestServerOperationSpecBinaryTest {

    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void deserializeShouldBindResponsesContract() throws Exception {
        String yaml = """
            method: "GET"
            name: "get-image"
            responses:
              '200':
                description: "Original image bytes"
                content:
                  image/jpeg:
                    binary: true
            """;

        RestServerOperationSpec op = mapper.readValue(yaml, RestServerOperationSpec.class);

        RestResponseSpec resp = op.getResponses().get("200");
        assertEquals("Original image bytes", resp.getDescription());
        assertTrue(resp.getContent().get("image/jpeg").isBinary());
    }

    @Test
    public void deserializeShouldBindResponseBinaryShorthand() throws Exception {
        String yaml = """
            method: "GET"
            name: "get-pdf"
            responseBinary:
              status: 200
              mediaType: "application/pdf"
              description: "Signed contract PDF"
            """;

        RestServerOperationSpec op = mapper.readValue(yaml, RestServerOperationSpec.class);

        assertEquals("application/pdf", op.getResponseBinary().getMediaType());
        assertEquals(200, op.getResponseBinary().getStatusOrDefault());
    }

    @Test
    public void responseBinaryShouldDefaultStatusTo200() {
        RestResponseBinarySpec shorthand = new RestResponseBinarySpec();
        shorthand.setMediaType("image/png");
        assertEquals(200, shorthand.getStatusOrDefault());
    }

    @Test
    public void getEffectiveResponsesShouldNormalizeShorthandIntoResponses() throws Exception {
        String yaml = """
            method: "GET"
            name: "get-pdf"
            responseBinary:
              mediaType: "application/pdf"
              description: "Signed contract PDF"
            """;

        RestServerOperationSpec op = mapper.readValue(yaml, RestServerOperationSpec.class);

        Map<String, RestResponseSpec> normalized = op.getEffectiveResponses();
        RestResponseSpec resp = normalized.get("200");
        assertEquals("Signed contract PDF", resp.getDescription());
        assertTrue(resp.getContent().get("application/pdf").isBinary());
    }

    @Test
    public void findBinaryResponseMediaTypeShouldReturnDeclaredType() throws Exception {
        String yaml = """
            method: "GET"
            name: "get-image"
            responses:
              '200':
                content:
                  image/jpeg:
                    binary: true
            """;

        RestServerOperationSpec op = mapper.readValue(yaml, RestServerOperationSpec.class);

        assertEquals(Optional.of("image/jpeg"), op.findBinaryResponseMediaType());
        assertTrue(op.hasBinaryResponse());
    }

    @Test
    public void hasBinaryResponseShouldBeFalseWhenNoContractDeclared() {
        RestServerOperationSpec op = new RestServerOperationSpec();
        op.setMethod("GET");
        op.setName("list");

        assertFalse(op.hasBinaryResponse());
        assertTrue(op.getEffectiveResponses().isEmpty());
    }

    @Test
    public void hasBinaryResponseShouldBeFalseForNonBinaryContent() throws Exception {
        String yaml = """
            method: "GET"
            name: "get-issue"
            responses:
              '200':
                content:
                  application/json:
                    schema:
                      type: object
            """;

        RestServerOperationSpec op = mapper.readValue(yaml, RestServerOperationSpec.class);

        assertFalse(op.hasBinaryResponse());
    }
}
