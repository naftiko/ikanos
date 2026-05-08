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
package io.ikanos.engine.exposes.control;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.control.ControlServerSpec;
import io.ikanos.spec.util.VersionHelper;
import org.restlet.routing.TemplateRoute;

import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Integration tests for Control Server Adapter.
 * Tests YAML deserialization, spec wiring, and adapter lifecycle.
 */
public class ControlIntegrationTest {

    private Capability capability;
    private String schemaVersion;

    @BeforeEach
    public void setUp() throws Exception {
        String resourcePath = "src/test/resources/control/control-capability.yaml";
        File file = new File(resourcePath);

        assertTrue(file.exists(), "Control capability test file should exist at " + resourcePath);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(file, IkanosSpec.class);

        capability = new Capability(spec);
        schemaVersion = VersionHelper.getSchemaVersion();
    }

    @Test
    public void capabilityShouldLoadSuccessfully() {
        assertNotNull(capability, "Capability should be initialized");
        assertNotNull(capability.getSpec(), "Capability spec should be loaded");
        assertEquals(schemaVersion, capability.getSpec().getIkanos());
    }

    @Test
    public void controlAdapterShouldBeCreated() {
        assertFalse(capability.getServerAdapters().isEmpty(),
                "Capability should have at least one server adapter");

        ServerAdapter adapter = capability.getServerAdapters().get(0);
        assertInstanceOf(ControlServerAdapter.class, adapter,
                "First server adapter should be ControlServerAdapter");
    }

    @Test
    public void controlServerSpecShouldBeDeserialized() {
        ControlServerAdapter adapter =
                (ControlServerAdapter) capability.getServerAdapters().get(0);
        ControlServerSpec spec = adapter.getControlServerSpec();

        assertEquals("control", spec.getType());
        assertEquals("localhost", spec.getAddress());
        assertEquals(9199, spec.getPort());
    }

    @Test
    public void endpointsShouldReflectYamlConfig() {
        ControlServerAdapter adapter =
                (ControlServerAdapter) capability.getServerAdapters().get(0);
        ControlServerSpec spec = adapter.getControlServerSpec();

        assertTrue(spec.getManagement().isHealth());
        assertTrue(spec.getManagement().isInfo());

        assertNotNull(spec.getObservability());
        assertTrue(spec.getObservability().getTraces().getLocal().isEnabled());
        assertEquals(50, spec.getObservability().getTraces().getLocal().getBufferSize());
    }

    @Test
    public void traceRingBufferShouldUseConfiguredCapacity() {
        ControlServerAdapter adapter =
                (ControlServerAdapter) capability.getServerAdapters().get(0);

        assertNotNull(adapter.getTraceRingBuffer());
        assertEquals(50, adapter.getTraceRingBuffer().getCapacity());
    }

    @Test
    public void routerShouldBeInitialized() {
        ControlServerAdapter adapter =
                (ControlServerAdapter) capability.getServerAdapters().get(0);

        assertNotNull(adapter.getRouter());
    }

    @Test
    public void routerShouldNotAttachMetricsOrTracesWhenObservabilityDisabled() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        IkanosSpec spec = mapper.readValue(
                new File("src/test/resources/control/control-observability-disabled.yaml"),
                IkanosSpec.class);
        Capability disabledCapability = new Capability(spec);
        ControlServerAdapter adapter =
                (ControlServerAdapter) disabledCapability.getServerAdapters().get(0);

        Set<String> patterns = adapter.getRouter().getRoutes().stream()
                .filter(TemplateRoute.class::isInstance)
                .map(route -> ((TemplateRoute) route).getTemplate().getPattern())
                .collect(Collectors.toSet());

        assertFalse(patterns.contains("/metrics"),
                "/metrics should not be attached when observability.enabled=false");
        assertFalse(patterns.contains("/traces"),
                "/traces should not be attached when observability.enabled=false");
        assertFalse(patterns.contains("/traces/{traceId}"),
                "/traces/{traceId} should not be attached when observability.enabled=false");
    }
}
