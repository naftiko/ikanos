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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.ikanos.engine.util.OperationStepExecutor;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;

/**
 * Runtime representation of a domain aggregate.
 *
 * <p>Wraps an {@link AggregateSpec} (YAML data) and owns executable
 * {@link AggregateFlow} instances for each flow defined in the spec.</p>
 */
public class Aggregate {

    private final String namespace;
    private final List<AggregateFlow> flows;

    public Aggregate(AggregateSpec spec, OperationStepExecutor stepExecutor) {
        this.namespace = spec.getNamespace();
        this.flows = new CopyOnWriteArrayList<>();
        for (AggregateFlowSpec flowSpec : spec.getFlows().values()) {
            this.flows.add(new AggregateFlow(flowSpec, stepExecutor, this.namespace));
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public List<AggregateFlow> getFlows() {
        return flows;
    }

    /**
     * Find a flow by name within this aggregate.
     *
     * @param name the flow name
     * @return the flow, or {@code null} if not found
     */
    public AggregateFlow findFlow(String name) {
        for (AggregateFlow flow : flows) {
            if (flow.getName().equals(name)) {
                return flow;
            }
        }
        return null;
    }

}
