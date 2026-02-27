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
package io.naftiko.engine.exposes;

import io.naftiko.Capability;
import io.naftiko.engine.Adapter;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * Base class for server adapters. Transport-agnostic — each subclass manages its own HTTP server
 * implementation (e.g. Restlet for API, Jetty for MCP).
 */
public abstract class ServerAdapter extends Adapter {

    private final Capability capability;
    private final ServerSpec spec;

    public ServerAdapter(Capability capability, ServerSpec spec) {
        this.capability = capability;
        this.spec = spec;
    }

    public Capability getCapability() {
        return capability;
    }

    public ServerSpec getSpec() {
        return spec;
    }

}