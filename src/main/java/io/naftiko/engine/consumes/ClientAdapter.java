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
package io.naftiko.engine.consumes;

import java.util.concurrent.atomic.AtomicReference;

import io.naftiko.Capability;
import io.naftiko.engine.Adapter;
import io.naftiko.spec.consumes.ClientSpec;
import io.naftiko.spec.consumes.http.HttpClientSpec;

/**
 * Client Adapter implementation.
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

    public void setSpec(HttpClientSpec spec) {
        this.spec.set(spec);
    }

}
