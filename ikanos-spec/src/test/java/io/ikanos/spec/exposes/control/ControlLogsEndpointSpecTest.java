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
package io.ikanos.spec.exposes.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Unit tests for {@link ControlLogsEndpointSpec} and {@link ControlLogsEndpointSpecDeserializer}.
 *
 * <p>The deserializer accepts three forms:
 * <ul>
 *   <li>{@code logs: true} — all log endpoints enabled</li>
 *   <li>{@code logs: false} — all log endpoints disabled</li>
 *   <li>{@code logs: {…}} — fine-grained configuration</li>
 * </ul>
 * Each form is exercised here, plus direct setter round-trip on the spec POJO.
 */
public class ControlLogsEndpointSpecTest {

    @Test
    public void newSpecShouldHaveDefaults() {
        ControlLogsEndpointSpec spec = new ControlLogsEndpointSpec();
        assertTrue(spec.isLevelControl());
        assertFalse(spec.isStream());
        assertEquals(5, spec.getMaxSubscribers());
    }

    @Test
    public void settersShouldRoundTripValues() {
        ControlLogsEndpointSpec spec = new ControlLogsEndpointSpec();
        spec.setLevelControl(false);
        spec.setStream(true);
        spec.setMaxSubscribers(20);

        assertFalse(spec.isLevelControl());
        assertTrue(spec.isStream());
        assertEquals(20, spec.getMaxSubscribers());
    }

    @Test
    public void deserializerShouldEnableAllEndpointsWhenYamlValueIsTrue() throws Exception {
        String yaml = "logs: true\n";

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ControlManagementSpec parent = mapper.readValue(yaml, ControlManagementSpec.class);
        ControlLogsEndpointSpec logs = parent.getLogs();

        assertTrue(logs.isLevelControl());
        assertTrue(logs.isStream());
        assertEquals(5, logs.getMaxSubscribers());
    }

    @Test
    public void deserializerShouldDisableAllEndpointsWhenYamlValueIsFalse() throws Exception {
        String yaml = "logs: false\n";

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ControlManagementSpec parent = mapper.readValue(yaml, ControlManagementSpec.class);
        ControlLogsEndpointSpec logs = parent.getLogs();

        assertFalse(logs.isLevelControl());
        assertFalse(logs.isStream());
    }

    @Test
    public void deserializerShouldHonorObjectFormFields() throws Exception {
        String yaml = """
                logs:
                  level-control: false
                  stream: true
                  max-subscribers: 3
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ControlManagementSpec parent = mapper.readValue(yaml, ControlManagementSpec.class);
        ControlLogsEndpointSpec logs = parent.getLogs();

        assertFalse(logs.isLevelControl());
        assertTrue(logs.isStream());
        assertEquals(3, logs.getMaxSubscribers());
    }

    @Test
    public void deserializerShouldKeepDefaultsForUnspecifiedObjectFields() throws Exception {
        String yaml = """
                logs:
                  stream: true
                """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ControlManagementSpec parent = mapper.readValue(yaml, ControlManagementSpec.class);
        ControlLogsEndpointSpec logs = parent.getLogs();

        // level-control + max-subscribers default values from the POJO are preserved
        assertTrue(logs.isLevelControl());
        assertTrue(logs.isStream());
        assertEquals(5, logs.getMaxSubscribers());
    }
}
