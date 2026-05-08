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
import io.ikanos.spec.aggregates.AggregateFunctionSpec;
import io.ikanos.spec.aggregates.AggregateSpec;

/**
 * Runtime representation of a domain aggregate.
 *
 * <p>Wraps an {@link AggregateSpec} (YAML data) and owns executable
 * {@link AggregateFunction} instances for each function defined in the spec.</p>
 */
public class Aggregate {

    private final String namespace;
    private final List<AggregateFunction> functions;

    public Aggregate(AggregateSpec spec, OperationStepExecutor stepExecutor) {
        this.namespace = spec.getNamespace();
        this.functions = new CopyOnWriteArrayList<>();
        for (AggregateFunctionSpec fnSpec : spec.getFunctions()) {
            this.functions.add(new AggregateFunction(fnSpec, stepExecutor, this.namespace));
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public List<AggregateFunction> getFunctions() {
        return functions;
    }

    /**
     * Find a function by name within this aggregate.
     *
     * @param name the function name
     * @return the function, or {@code null} if not found
     */
    public AggregateFunction findFunction(String name) {
        for (AggregateFunction fn : functions) {
            if (fn.getName().equals(name)) {
                return fn;
            }
        }
        return null;
    }

}
