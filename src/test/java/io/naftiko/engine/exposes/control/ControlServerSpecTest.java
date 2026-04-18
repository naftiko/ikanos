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
package io.naftiko.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.spec.exposes.ControlEndpointsSpec;
import io.naftiko.spec.exposes.ControlServerSpec;
import io.naftiko.spec.exposes.ControlTracesEndpointSpec;
import io.naftiko.spec.exposes.ControlLogsEndpointSpec;

/**
 * Unit tests for control port spec deserialization.
 */
public class ControlServerSpecTest {

    @Test
    public void controlSpecShouldDeserializeFromYaml() throws Exception {
        String yaml = """
                type: "control"
                address: "localhost"
                port: 9100
                endpoints:
                  health: true
                  metrics: false
                  info: true
                  traces:
                    enabled: true
                    buffer-size: 200
                  logs:
                    stream: true
                    max-subscribers: 10
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);

        assertEquals("control", spec.getType());
        assertEquals("localhost", spec.getAddress());
        assertEquals(9100, spec.getPort());

        ControlEndpointsSpec endpoints = spec.getEndpoints();
        assertTrue(endpoints.isHealth());
        assertFalse(endpoints.isMetrics());
        assertTrue(endpoints.isInfo());
        assertFalse(endpoints.isReload());
        assertFalse(endpoints.isValidate());
        assertFalse(endpoints.isLogging());

        ControlTracesEndpointSpec traces = endpoints.getTraces();
        assertTrue(traces.isEnabled());
        assertEquals(200, traces.getBufferSize());

        ControlLogsEndpointSpec logs = endpoints.getLogs();
        assertTrue(logs.isStream());
        assertEquals(10, logs.getMaxSubscribers());
    }

    @Test
    public void controlSpecShouldUseDefaultsWhenEndpointsOmitted() throws Exception {
        String yaml = """
                type: "control"
                address: "localhost"
                port: 9100
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);

        ControlEndpointsSpec endpoints = spec.getEndpoints();
        assertNotNull(endpoints);
        assertTrue(endpoints.isHealth());
        assertTrue(endpoints.isMetrics());
        assertFalse(endpoints.isInfo());

        ControlTracesEndpointSpec traces = endpoints.getTraces();
        assertNotNull(traces);
        assertTrue(traces.isEnabled());
        assertEquals(100, traces.getBufferSize());

        ControlLogsEndpointSpec logs = endpoints.getLogs();
        assertNotNull(logs);
        assertFalse(logs.isStream());
        assertEquals(5, logs.getMaxSubscribers());
    }

    @Test
    public void controlSpecShouldDeserializeMinimalConfig() throws Exception {
        String yaml = """
                type: "control"
                port: 9100
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);

        assertEquals("control", spec.getType());
        assertEquals(9100, spec.getPort());
        assertNotNull(spec.getEndpoints());
    }

    private static <T> T parseYaml(String yaml, Class<T> type) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(yaml, type);
    }
}
