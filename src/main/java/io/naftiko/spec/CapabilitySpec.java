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
import com.fasterxml.jackson.annotation.JsonInclude;
import io.naftiko.spec.aggregates.AggregateSpec;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.exposes.ServerSpec;
import io.naftiko.spec.util.BindingSpec;

/**
 * Capability Specification Element
 */
public class CapabilitySpec {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<BindingSpec> binds;
    private final List<ServerSpec> exposes;
    private final List<ClientSpec> consumes;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<AggregateSpec> aggregates;

    public CapabilitySpec() {
        this.binds = new CopyOnWriteArrayList<>();
        this.exposes = new CopyOnWriteArrayList<>();
        this.consumes = new CopyOnWriteArrayList<>();
        this.aggregates = new CopyOnWriteArrayList<>();
    }

    public List<BindingSpec> getBinds() {
        return binds;
    }

    public List<ServerSpec> getExposes() {
        return exposes;
    }

    public List<ClientSpec> getConsumes() {
        return consumes;
    }

    public List<AggregateSpec> getAggregates() {
        return aggregates;
    }

}
