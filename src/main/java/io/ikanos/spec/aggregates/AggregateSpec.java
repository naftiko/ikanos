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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregate Specification Element.
 *
 * <p>A domain aggregate grouping reusable functions. Adapters reference these functions via ref.</p>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference} so that fluent builders and
 * Control-port runtime edits can replace values atomically while engine threads read them.
 * The {@code functions} collection is a {@link CopyOnWriteArrayList} which provides the same
 * guarantee at the element level. This satisfies SonarQube rule {@code java:S3077}.
 */
public class AggregateSpec {

    private final AtomicReference<String> label = new AtomicReference<>();
    private final AtomicReference<String> namespace = new AtomicReference<>();
    private final List<AggregateFunctionSpec> functions;

    public AggregateSpec() {
        this.functions = new CopyOnWriteArrayList<>();
    }

    public String getLabel() {
        return label.get();
    }

    public void setLabel(String label) {
        this.label.set(label);
    }

    public String getNamespace() {
        return namespace.get();
    }

    public void setNamespace(String namespace) {
        this.namespace.set(namespace);
    }

    public List<AggregateFunctionSpec> getFunctions() {
        return functions;
    }

}
