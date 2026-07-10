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
package io.ikanos.spec.consumes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for the binary-content fields added to {@link HttpClientOperationSpec}
 * ({@code outputMediaType} and {@code maxBinarySize}) — the Java side of the Phase&nbsp;0 schema
 * additions, consumed at runtime starting in Phase&nbsp;1. See
 * {@code blueprints/capability-binary-content.md} §5.1.
 */
public class HttpClientOperationSpecBinaryTest {

    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void deserializeShouldBindOutputMediaTypeAndMaxBinarySize() throws Exception {
        String yaml = """
            method: "GET"
            name: "download"
            outputRawFormat: "binary"
            outputMediaType: "image/jpeg"
            maxBinarySize: "5MiB"
            """;

        HttpClientOperationSpec op = mapper.readValue(yaml, HttpClientOperationSpec.class);

        assertEquals("binary", op.getOutputRawFormat());
        assertEquals("image/jpeg", op.getOutputMediaType());
        assertEquals("5MiB", op.getMaxBinarySize());
    }

    @Test
    public void binaryFieldsShouldDefaultToNullWhenAbsent() throws Exception {
        String yaml = """
            method: "GET"
            name: "list"
            """;

        HttpClientOperationSpec op = mapper.readValue(yaml, HttpClientOperationSpec.class);

        assertNull(op.getOutputMediaType());
        assertNull(op.getMaxBinarySize());
    }

    @Test
    public void settersShouldRoundTripBinaryFields() {
        HttpClientOperationSpec op = new HttpClientOperationSpec();

        op.setOutputMediaType("application/pdf");
        op.setMaxBinarySize("1GiB");

        assertEquals("application/pdf", op.getOutputMediaType());
        assertEquals("1GiB", op.getMaxBinarySize());
    }
}
