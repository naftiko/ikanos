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
package io.naftiko.spec;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregate Specification Element.
 * 
 * A domain aggregate grouping reusable functions. Adapters reference these functions via ref.
 */
public class AggregateSpec {

    private volatile String label;
    private volatile String namespace;
    private final List<AggregateFunctionSpec> functions;

    public AggregateSpec() {
        this.functions = new CopyOnWriteArrayList<>();
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public List<AggregateFunctionSpec> getFunctions() {
        return functions;
    }

}
