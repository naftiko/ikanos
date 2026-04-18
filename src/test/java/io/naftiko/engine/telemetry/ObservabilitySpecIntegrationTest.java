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
package io.naftiko.engine.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.ObservabilitySpec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/**
 * Integration tests for spec-driven observability configuration. Loads full YAML capability
 * fixtures and validates end-to-end deserialization of the observability block.
 */
class ObservabilitySpecIntegrationTest {

    @Test
    void fullCapabilityShouldDeserializeObservabilityConfig() throws Exception {
        NaftikoSpec spec = loadFixture("observability/observability-capability.yaml");

        assertNotNull(spec.getCapability());
        ObservabilitySpec observability = spec.getCapability().getObservability();
        assertNotNull(observability);
        assertTrue(observability.isEnabled());

        assertNotNull(observability.getTraces());
        assertEquals(0.5, observability.getTraces().getSampling(), 0.001);
        assertEquals("b3", observability.getTraces().getPropagation());

        assertNotNull(observability.getExporters());
        assertNotNull(observability.getExporters().getOtlp());
        assertEquals("http://collector.local:4317",
                observability.getExporters().getOtlp().getEndpoint());
    }

    @Test
    void disabledCapabilityShouldDeserializeObservabilityConfig() throws Exception {
        NaftikoSpec spec = loadFixture("observability/observability-disabled.yaml");

        assertNotNull(spec.getCapability());
        ObservabilitySpec observability = spec.getCapability().getObservability();
        assertNotNull(observability);
        assertFalse(observability.isEnabled());
        assertNull(observability.getTraces());
        assertNull(observability.getExporters());
    }

    @Test
    void capabilityWithoutObservabilityShouldDeserialize() throws Exception {
        NaftikoSpec spec = loadFixture("control/control-capability.yaml");

        assertNotNull(spec.getCapability());
        assertNull(spec.getCapability().getObservability(),
                "Observability should be null when not declared in YAML");
    }

    @Test
    void specPropertiesShouldMapCorrectlyFromYamlFixture() throws Exception {
        NaftikoSpec spec = loadFixture("observability/observability-capability.yaml");
        ObservabilitySpec observability = spec.getCapability().getObservability();

        var props = TelemetryBootstrap.buildSpecProperties(observability);

        assertEquals("traceidratio", props.get("otel.traces.sampler"));
        assertEquals("0.5", props.get("otel.traces.sampler.arg"));
        assertEquals("b3multi", props.get("otel.propagators"));
        assertEquals("http://collector.local:4317", props.get("otel.exporter.otlp.endpoint"));
    }

    private static NaftikoSpec loadFixture(String resourcePath) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = ObservabilitySpecIntegrationTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Fixture not found: " + resourcePath);
            return mapper.readValue(is, NaftikoSpec.class);
        }
    }
}
