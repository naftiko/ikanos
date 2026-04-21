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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.naftiko.Capability;
import io.naftiko.spec.NaftikoSpec;
import io.naftiko.spec.ObservabilitySpec;
import io.naftiko.spec.ObservabilityMetricsSpec;
import io.naftiko.spec.ObservabilityLocalEndpointSpec;
import io.naftiko.spec.ObservabilityTracesSpec;
import io.naftiko.spec.ObservabilityTracesLocalSpec;

import java.io.File;

/**
 * Unit tests for ControlServerAdapter helper methods — isMetricsEnabled, isTracesEnabled.
 * Methods are package-private, tested directly from the same package.
 */
public class ControlServerAdapterTest {

    private ControlServerAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NaftikoSpec spec = mapper.readValue(
                new File("src/test/resources/control/control-capability.yaml"),
                NaftikoSpec.class);
        Capability capability = new Capability(spec);
        adapter = (ControlServerAdapter) capability.getServerAdapters().get(0);
    }

    // ── isMetricsEnabled ──

    @Test
    void isMetricsEnabledShouldReturnTrueWhenObservabilityIsNull() {
        assertTrue(adapter.isMetricsEnabled(null));
    }

    @Test
    void isMetricsEnabledShouldReturnFalseWhenObservabilityDisabled() {
        ObservabilitySpec obs = new ObservabilitySpec();
        obs.setEnabled(false);

        assertFalse(adapter.isMetricsEnabled(obs));
    }

    @Test
    void isMetricsEnabledShouldReturnTrueWhenObservabilityEnabledAndLocalNotSet() {
        ObservabilitySpec obs = new ObservabilitySpec();
        obs.setEnabled(true);

        assertTrue(adapter.isMetricsEnabled(obs));
    }

    @Test
    void isMetricsEnabledShouldReturnFalseWhenLocalMetricsDisabled() {
        ObservabilitySpec obs = new ObservabilitySpec();
        ObservabilityMetricsSpec metrics = new ObservabilityMetricsSpec();
        ObservabilityLocalEndpointSpec local = new ObservabilityLocalEndpointSpec();
        local.setEnabled(false);
        metrics.setLocal(local);
        obs.setMetrics(metrics);

        assertFalse(adapter.isMetricsEnabled(obs));
    }

    // ── isTracesEnabled ──

    @Test
    void isTracesEnabledShouldReturnTrueWhenObservabilityIsNull() {
        assertTrue(adapter.isTracesEnabled(null));
    }

    @Test
    void isTracesEnabledShouldReturnFalseWhenObservabilityDisabled() {
        ObservabilitySpec obs = new ObservabilitySpec();
        obs.setEnabled(false);

        assertFalse(adapter.isTracesEnabled(obs));
    }

    @Test
    void isTracesEnabledShouldReturnTrueWhenObservabilityEnabledAndLocalNotSet() {
        ObservabilitySpec obs = new ObservabilitySpec();
        obs.setEnabled(true);

        assertTrue(adapter.isTracesEnabled(obs));
    }

    @Test
    void isTracesEnabledShouldReturnFalseWhenLocalTracesDisabled() {
        ObservabilitySpec obs = new ObservabilitySpec();
        ObservabilityTracesSpec traces = new ObservabilityTracesSpec();
        ObservabilityTracesLocalSpec local = new ObservabilityTracesLocalSpec();
        local.setEnabled(false);
        traces.setLocal(local);
        obs.setTraces(traces);

        assertFalse(adapter.isTracesEnabled(obs));
    }
}
