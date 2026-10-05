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
package io.ikanos.engine.consumes;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.ikanos.Capability;
import io.ikanos.engine.Adapter;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * Client Adapter implementation.
 *
 * <p>Every consumed adapter, whatever its protocol, is reached from steps and exposers through
 * {@link #prepare(String, Map)}, which returns a protocol-neutral {@link ConsumedInvocation}. See
 * {@code design-docs/consumed-invocation-carrier.md}.</p>
 *
 * <h2>Thread safety</h2>
 * The {@code capability} and {@code spec} references are held in {@link AtomicReference}s so
 * that a future Control-port "hot reload" feature can replace them atomically while request
 * threads read them. This satisfies SonarQube rule {@code java:S3077}.
 */
public abstract class ClientAdapter extends Adapter {

    private final AtomicReference<Capability> capability = new AtomicReference<>();

    private final AtomicReference<ClientSpec> spec = new AtomicReference<>();

    public ClientAdapter(Capability capability, ClientSpec spec) {
        this.capability.set(capability);
        this.spec.set(spec);
    }

    public Capability getCapability() {
        return capability.get();
    }

    public void setCapability(Capability capability) {
        this.capability.set(capability);
    }

    public ClientSpec getSpec() {
        return spec.get();
    }

    public void setSpec(ClientSpec spec) {
        this.spec.set(spec);
    }

    /** @return the namespace declared on this adapter's spec, or {@code null} */
    public String getNamespace() {
        ClientSpec current = spec.get();
        return current != null ? current.getNamespace() : null;
    }

    /**
     * Build an invocation for one declared operation. Nothing is sent until
     * {@link ConsumedInvocation#invoke()}.
     *
     * @param operationName the operation name as declared under this adapter
     * @param parameters    resolved parameters available for template substitution
     * @return the invocation, or {@code null} when this adapter declares no such operation
     * @throws IllegalArgumentException when templates cannot be resolved
     */
    public abstract ConsumedInvocation prepare(String operationName,
            Map<String, Object> parameters);

}
