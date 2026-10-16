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

import java.util.HashMap;
import java.util.Map;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;

/**
 * A call to another aggregate flow, made from a {@code call} step or a flow-level {@code call:}.
 *
 * <p>This is one more implementation of {@link ConsumedInvocation}: the step orchestration
 * treats a called flow exactly like a consumed operation, so a flow and an operation are
 * interchangeable behind {@code call: namespace.name}. The inherited {@link #invoke()} opens a
 * client span around the flow, which nests the called flow's own spans under the calling step.</p>
 */
public class FlowInvocation extends ConsumedInvocation {

    private final AggregateFlow flow;
    private final Map<String, Object> parameters;

    /**
     * @param flow       the flow to call
     * @param parameters the caller's parameters, already merged with the step's {@code with:}
     */
    public FlowInvocation(AggregateFlow flow, Map<String, Object> parameters) {
        this.flow = flow;
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
    }

    /** @return the flow this invocation calls */
    public AggregateFlow flow() {
        return flow;
    }

    @Override
    public String namespace() {
        return flow.getNamespace();
    }

    @Override
    public ConsumedOperationView operation() {
        return FlowConsumedResult.viewOf(flow);
    }

    @Override
    protected ConsumedResult doInvoke() throws Exception {
        return new FlowConsumedResult(flow, flow.execute(parameters));
    }

    @Override
    protected ClientSpanDescriptor spanDescriptor() {
        return new ClientSpanDescriptor("flow", "flow://" + flow.getRef());
    }
}
