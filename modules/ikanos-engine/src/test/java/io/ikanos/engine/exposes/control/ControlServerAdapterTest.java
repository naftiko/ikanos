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
import tools.jackson.databind.ObjectMapper;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.observability.ObservabilitySpec;
import io.ikanos.spec.observability.ObservabilityMetricsSpec;
import io.ikanos.spec.observability.ObservabilityLocalEndpointSpec;
import io.ikanos.spec.observability.ObservabilityTracesSpec;
import io.ikanos.spec.observability.ObservabilityTracesLocalSpec;

import java.io.File;
import java.util.List;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.Method;
import org.restlet.data.Status;
import com.nimbusds.jwt.JWTClaimsSet;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.OAuth2AuthenticationRestlet;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Unit tests for ControlServerAdapter helper methods — isMetricsEnabled, isTracesEnabled.
 * Methods are package-private, tested directly from the same package.
 */
public class ControlServerAdapterTest {

    private ControlServerAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        IkanosSpec spec = mapper.readValue(
                new File("src/test/resources/control/control-capability.yaml"),
                IkanosSpec.class);
        Capability capability = new Capability(spec);
        adapter = (ControlServerAdapter) capability.getServerAdapters().get(0);
    }

    // ── requireAllScopes (#999: the control port keeps requiring its listed scopes) ──

    private static Request requestWithScopes(String scopes) {
        Request request = new Request(Method.GET, "http://localhost:9199/traces");
        request.getAttributes().put(OAuth2AuthenticationRestlet.VALIDATED_CLAIMS_ATTRIBUTE,
                new JWTClaimsSet.Builder().claim("scope", scopes).build());
        return request;
    }

    private static Restlet okRestlet(boolean[] called) {
        return new Restlet() {
            @Override
            public void handle(Request request, Response response) {
                called[0] = true;
                response.setStatus(Status.SUCCESS_OK);
            }
        };
    }

    @Test
    void requireAllScopesShouldRefuseTokenMissingOneListedScope() {
        boolean[] called = {false};
        Request request = requestWithScopes("control.read");
        Response response = new Response(request);

        ControlServerAdapter.requireAllScopes(List.of("control.read", "control.admin"),
                okRestlet(called)).handle(request, response);

        assertFalse(called[0]);
        assertEquals(Status.CLIENT_ERROR_FORBIDDEN, response.getStatus());
        AccessControl.Denial denial = assertInstanceOf(AccessControl.Denial.class,
                response.getAttributes().get(AccessControl.DENIAL_ATTRIBUTE));
        assertEquals(List.of("control.read", "control.admin"), denial.scopes());
    }

    @Test
    void requireAllScopesShouldLetTokenCarryingEveryListedScopeThrough() {
        boolean[] called = {false};
        Request request = requestWithScopes("control.admin control.read");
        Response response = new Response(request);

        ControlServerAdapter.requireAllScopes(List.of("control.read", "control.admin"),
                okRestlet(called)).handle(request, response);

        assertTrue(called[0]);
        assertEquals(Status.SUCCESS_OK, response.getStatus());
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
