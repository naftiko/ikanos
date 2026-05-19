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
package io.ikanos.spec.aggregates;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.spec.IkanosSpec;
import org.junit.jupiter.api.Test;

/**
 * Non-regression tests for the {@code flows:} rename (#463).
 *
 * <p>Verifies that a YAML capability document using {@code aggregates.<ns>.flows:} is correctly
 * deserialized into {@link AggregateFlowSpec} objects, and that the injected key becomes the
 * flow name.
 */
class AggregateFlowDeserializationTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    private static final String CAPABILITY_WITH_FLOWS = """
            ikanos: "1.0.0-alpha3"
            capability:
              exposes: []
              consumes: []
              aggregates:
                forecast:
                  display: "Forecast"
                  flows:
                    get-forecast:
                      description: "Retrieve forecast for a location"
                      call: weather-api.get-forecast
                    list-forecasts:
                      description: "List recent forecasts"
                      call: weather-api.list-forecasts
            """;

    @Test
    void flowsKeyShouldDeserializeIntoAggregateFlowSpecMap() throws Exception {
        IkanosSpec spec = MAPPER.readValue(CAPABILITY_WITH_FLOWS, IkanosSpec.class);

        AggregateSpec aggregate = spec.getCapability().getAggregates().get("forecast");
        assertNotNull(aggregate, "aggregate 'forecast' should be present");
        assertNotNull(aggregate.getFlows(), "flows map should not be null");
        assertEquals(2, aggregate.getFlows().size(), "should have 2 flows");
    }

    @Test
    void flowKeysShouldBeInjectedAsFlowNames() throws Exception {
        IkanosSpec spec = MAPPER.readValue(CAPABILITY_WITH_FLOWS, IkanosSpec.class);

        AggregateSpec aggregate = spec.getCapability().getAggregates().get("forecast");
        AggregateFlowSpec getForecast = aggregate.getFlows().get("get-forecast");
        AggregateFlowSpec listForecasts = aggregate.getFlows().get("list-forecasts");

        assertNotNull(getForecast, "flow 'get-forecast' should be present");
        assertEquals("get-forecast", getForecast.getName(),
                "YAML map key must be injected as the flow name");

        assertNotNull(listForecasts, "flow 'list-forecasts' should be present");
        assertEquals("list-forecasts", listForecasts.getName(),
                "YAML map key must be injected as the flow name");
    }

    @Test
    void flowDescriptionShouldBeDeserializedCorrectly() throws Exception {
        IkanosSpec spec = MAPPER.readValue(CAPABILITY_WITH_FLOWS, IkanosSpec.class);

        AggregateFlowSpec getForecast =
                spec.getCapability().getAggregates().get("forecast").getFlows().get("get-forecast");

        assertEquals("Retrieve forecast for a location", getForecast.getDescription());
    }
}
