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

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.Protocol;
import io.naftiko.Capability;
import io.naftiko.engine.Adapter;
import io.naftiko.spec.exposes.ServerSpec;

/**
 * Base class for server adapters. All HTTP-based adapters share the same Restlet {@link Server}
 * lifecycle: create, start, stop. Subclasses configure routing in their constructor and call
 * {@link #initServer(String, int, Restlet)} to wire the transport.
 */
public abstract class ServerAdapter extends Adapter {

    private final Capability capability;
    private final ServerSpec spec;
    private Server server;

    public ServerAdapter(Capability capability, ServerSpec spec) {
        this.capability = capability;
        this.spec = spec;
    }

    /**
     * Initialize the Restlet HTTP server. Subclasses call this after building their router/chain.
     */
    protected void initServer(String address, int port, Restlet handler) {
        this.server = new Server(Protocol.HTTP, address, port);
        this.server.setContext(new Context());

        // TODO: Make idle timeout configurable
        this.server.getContext().getParameters().add("socketTimeout", "12000");

        this.server.setNext(handler);
    }

    public Capability getCapability() {
        return capability;
    }

    public ServerSpec getSpec() {
        return spec;
    }

    public Server getServer() {
        return server;
    }

    @Override
    public void start() throws Exception {
        if (server != null) {
            server.start();
        }
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

}