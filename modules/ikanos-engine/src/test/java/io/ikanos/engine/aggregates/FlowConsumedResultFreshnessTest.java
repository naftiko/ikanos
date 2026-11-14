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
package io.ikanos.engine.aggregates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.Freshness;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * {@link FlowConsumedResult#freshness()} speaks for its upstream only when the flow is a single
 * call: with steps, the result depends on every step, not only the last one (#1019).
 */
class FlowConsumedResultFreshnessTest {

    private static final Freshness LAST_STEP = new Freshness(300_000L, false);

    private static ConsumedResult lastAnswer() {
        ConsumedResult last = mock(ConsumedResult.class);
        when(last.freshness()).thenReturn(LAST_STEP);
        return last;
    }

    @Test
    void freshnessShouldBeTheUpstreamFreshnessOfASingleCallFlow() throws Exception {
        AggregateFlow flow = mock(AggregateFlow.class);
        when(flow.getCall()).thenReturn(mock(ServerCallSpec.class));

        FlowConsumedResult result =
                new FlowConsumedResult(flow, new FlowResult(lastAnswer(), null, null));

        assertThat(result.freshness()).isEqualTo(LAST_STEP);
    }

    @Test
    void freshnessShouldBeUnknownForAFlowWithSeveralSteps() throws Exception {
        // step 1 said no-store, step 2 max-age=300: the last answer does not speak for the result
        Map<String, OperationStepSpec> steps = new LinkedHashMap<>();
        steps.put("first", mock(OperationStepSpec.class));
        steps.put("second", mock(OperationStepSpec.class));
        AggregateFlow flow = mock(AggregateFlow.class);
        when(flow.getSteps()).thenReturn(steps);

        FlowConsumedResult result =
                new FlowConsumedResult(flow, new FlowResult(lastAnswer(), null, null));

        assertThat(result.freshness()).isEqualTo(Freshness.UNKNOWN);
    }

    @Test
    void freshnessShouldBeUnknownWhenTheFlowShapesItsOwnOutput() throws Exception {
        AggregateFlow flow = mock(AggregateFlow.class);
        when(flow.getCall()).thenReturn(mock(ServerCallSpec.class));

        FlowConsumedResult result =
                new FlowConsumedResult(flow, new FlowResult(lastAnswer(), "{\"a\":1}", null));

        assertThat(result.freshness()).isEqualTo(Freshness.UNKNOWN);
    }
}
