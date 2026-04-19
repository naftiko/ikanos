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
import io.naftiko.spec.ObservabilitySpec;
import io.naftiko.spec.ObservabilityTracesLocalSpec;
import io.naftiko.spec.exposes.ControlManagementSpec;
import io.naftiko.spec.exposes.ControlServerSpec;
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
                management:
                  health: true
                  info: true
                  logs:
                    stream: true
                    max-subscribers: 10
                observability:
                  metrics:
                    local:
                      enabled: false
                  traces:
                    sampling: 0.5
                    local:
                      enabled: true
                      buffer-size: 200
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);

        assertEquals("control", spec.getType());
        assertEquals("localhost", spec.getAddress());
        assertEquals(9100, spec.getPort());

        ControlManagementSpec management = spec.getManagement();
        assertTrue(management.isHealth());
        assertTrue(management.isInfo());
        assertFalse(management.isReload());
        assertFalse(management.isValidate());
        assertTrue(management.isLogging());

        ControlLogsEndpointSpec logs = management.getLogs();
        assertTrue(logs.isLevelControl());
        assertTrue(logs.isStream());
        assertEquals(10, logs.getMaxSubscribers());

        ObservabilitySpec observability = spec.getObservability();
        assertNotNull(observability);
        assertTrue(observability.isEnabled());
        assertFalse(observability.getMetrics().getLocal().isEnabled());

        ObservabilityTracesLocalSpec tracesLocal = observability.getTraces().getLocal();
        assertTrue(tracesLocal.isEnabled());
        assertEquals(200, tracesLocal.getBufferSize());
    }

    @Test
    public void controlSpecShouldUseDefaultsWhenManagementOmitted() throws Exception {
        String yaml = """
                type: "control"
                address: "localhost"
                port: 9100
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);

        ControlManagementSpec management = spec.getManagement();
        assertNotNull(management);
        assertTrue(management.isHealth());
        assertFalse(management.isInfo());
        assertFalse(management.isLogging());

        ControlLogsEndpointSpec logs = management.getLogs();
        assertNotNull(logs);
        assertFalse(logs.isLevelControl());
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
        assertNotNull(spec.getManagement());
    }

    @Test
    public void logsShouldDeserializeBooleanTrue() throws Exception {
        String yaml = """
                type: "control"
                port: 9100
                management:
                  logs: true
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);
        ControlLogsEndpointSpec logs = spec.getManagement().getLogs();

        assertTrue(logs.isLevelControl());
        assertTrue(logs.isStream());
        assertEquals(5, logs.getMaxSubscribers());
        assertTrue(spec.getManagement().isLogging());
    }

    @Test
    public void logsShouldDeserializeBooleanFalse() throws Exception {
        String yaml = """
                type: "control"
                port: 9100
                management:
                  logs: false
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);
        ControlLogsEndpointSpec logs = spec.getManagement().getLogs();

        assertFalse(logs.isLevelControl());
        assertFalse(logs.isStream());
        assertFalse(spec.getManagement().isLogging());
    }

    @Test
    public void logsShouldDeserializeObjectWithLevelControlDisabled() throws Exception {
        String yaml = """
                type: "control"
                port: 9100
                management:
                  logs:
                    level-control: false
                    stream: true
                    max-subscribers: 3
                """;

        ControlServerSpec spec = parseYaml(yaml, ControlServerSpec.class);
        ControlLogsEndpointSpec logs = spec.getManagement().getLogs();

        assertFalse(logs.isLevelControl());
        assertTrue(logs.isStream());
        assertEquals(3, logs.getMaxSubscribers());
        assertFalse(spec.getManagement().isLogging());
    }

    private static <T> T parseYaml(String yaml, Class<T> type) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(yaml, type);
    }
}
