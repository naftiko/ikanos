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
package io.ikanos.spec;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.util.BindingSpec;

/**
 * Ikanos Specification Root, including version and capabilities.
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference} so that fluent builders and
 * Control-port runtime edits can replace values atomically while engine threads read them.
 * The {@code binds} and {@code consumes} collections are {@link CopyOnWriteArrayList}s.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
public class IkanosSpec {

    private final AtomicReference<String> ikanos = new AtomicReference<>();
    private final AtomicReference<InfoSpec> info = new AtomicReference<>();
    private final AtomicReference<CapabilitySpec> capability = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<BindingSpec> binds;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<ClientSpec> consumes;

    public IkanosSpec(String ikanos, InfoSpec info, CapabilitySpec capability) {
        this.ikanos.set(ikanos);
        this.info.set(info);
        this.binds = new CopyOnWriteArrayList<>();
        this.capability.set(capability);
        this.consumes = new CopyOnWriteArrayList<>();
    }

    public IkanosSpec() {
        this(null, null, null);
    }

    public String getIkanos() {
        return ikanos.get();
    }

    public void setIkanos(String ikanos) {
        this.ikanos.set(ikanos);
    }

    public InfoSpec getInfo() {
        return info.get();
    }

    public void setInfo(InfoSpec info) {
        this.info.set(info);
    }

    public List<BindingSpec> getBinds() {
        return binds;
    }

    public CapabilitySpec getCapability() {
        return capability.get();
    }

    public void setCapability(CapabilitySpec capability) {
        this.capability.set(capability);
    }

    public List<ClientSpec> getConsumes() {
        return consumes;
    }

}
