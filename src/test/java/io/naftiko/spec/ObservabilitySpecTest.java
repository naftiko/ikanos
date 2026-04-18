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
package io.naftiko.spec;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for ObservabilitySpec deserialization.
 */
class ObservabilitySpecTest {

    @Test
    void observabilityShouldDeserializeFullConfig() throws Exception {
        String yaml = """
                enabled: true
                traces:
                  sampling: 0.1
                  propagation: b3
                exporters:
                  otlp:
                    endpoint: "http://localhost:4317"
                """;

        ObservabilitySpec spec = parseYaml(yaml, ObservabilitySpec.class);

        assertTrue(spec.isEnabled());

        ObservabilityTracesSpec traces = spec.getTraces();
        assertNotNull(traces);
        assertEquals(0.1, traces.getSampling(), 0.001);
        assertEquals("b3", traces.getPropagation());

        ObservabilityExportersSpec exporters = spec.getExporters();
        assertNotNull(exporters);
        assertNotNull(exporters.getOtlp());
        assertEquals("http://localhost:4317", exporters.getOtlp().getEndpoint());
    }

    @Test
    void observabilityShouldDeserializeDisabledConfig() throws Exception {
        String yaml = """
                enabled: false
                """;

        ObservabilitySpec spec = parseYaml(yaml, ObservabilitySpec.class);

        assertFalse(spec.isEnabled());
        assertNull(spec.getTraces());
        assertNull(spec.getExporters());
    }

    @Test
    void observabilityShouldUseDefaultsWhenEmpty() throws Exception {
        String yaml = "{}";

        ObservabilitySpec spec = parseYaml(yaml, ObservabilitySpec.class);

        assertTrue(spec.isEnabled());
        assertNull(spec.getTraces());
        assertNull(spec.getExporters());
    }

    @Test
    void tracesSpecShouldUseDefaultValues() throws Exception {
        String yaml = "{}";

        ObservabilityTracesSpec spec = parseYaml(yaml, ObservabilityTracesSpec.class);

        assertEquals(1.0, spec.getSampling(), 0.001);
        assertEquals("w3c", spec.getPropagation());
    }

    @Test
    void tracesSpecShouldDeserializePartialConfig() throws Exception {
        String yaml = """
                sampling: 0.5
                """;

        ObservabilityTracesSpec spec = parseYaml(yaml, ObservabilityTracesSpec.class);

        assertEquals(0.5, spec.getSampling(), 0.001);
        assertEquals("w3c", spec.getPropagation());
    }

    @Test
    void observabilityShouldDeserializeTracesOnlyConfig() throws Exception {
        String yaml = """
                traces:
                  sampling: 0.25
                  propagation: w3c
                """;

        ObservabilitySpec spec = parseYaml(yaml, ObservabilitySpec.class);

        assertTrue(spec.isEnabled());
        assertNotNull(spec.getTraces());
        assertEquals(0.25, spec.getTraces().getSampling(), 0.001);
        assertEquals("w3c", spec.getTraces().getPropagation());
        assertNull(spec.getExporters());
    }

    @Test
    void observabilityShouldDeserializeExportersOnlyConfig() throws Exception {
        String yaml = """
                exporters:
                  otlp:
                    endpoint: "https://otlp.example.com:4317"
                """;

        ObservabilitySpec spec = parseYaml(yaml, ObservabilitySpec.class);

        assertTrue(spec.isEnabled());
        assertNull(spec.getTraces());
        assertNotNull(spec.getExporters());
        assertEquals("https://otlp.example.com:4317", spec.getExporters().getOtlp().getEndpoint());
    }

    private static <T> T parseYaml(String yaml, Class<T> type) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(yaml, type);
    }
}
