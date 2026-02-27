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

import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.routing.Router;
import org.restlet.routing.TemplateRoute;
import org.restlet.routing.Variable;
import io.naftiko.Capability;
import io.naftiko.spec.exposes.ApiServerResourceSpec;
import io.naftiko.spec.exposes.ApiServerSpec;

/**
 * Implementation of the ServerAdapter abstract class that sets up an HTTP server using the Restlet
 * Framework acting as a spec-driven API server.
 */
public class ApiServerAdapter extends ServerAdapter {

    private final Server server;
    private final Router router;

    public ApiServerAdapter(Capability capability, ApiServerSpec serverSpec) {
        super(capability, serverSpec);
        this.server = new Server(Protocol.HTTP, serverSpec.getAddress(), serverSpec.getPort());
        this.router = new Router();
        this.server.setNext(this.router);

        for (ApiServerResourceSpec res : getApiServerSpec().getResources()) {
            String pathTemplate = toUriTemplate(res.getPath());
            Restlet resourceRestlet = new ApiResourceRestlet(capability, serverSpec, res);
            TemplateRoute route = getRouter().attach(pathTemplate, resourceRestlet);
            route.getTemplate().getVariables().put("path", new Variable(Variable.TYPE_URI_PATH));
        }
    }

    public ApiServerSpec getApiServerSpec() {
        return (ApiServerSpec) getSpec();
    }

    public Server getServer() {
        return server;
    }

    public Router getRouter() {
        return router;
    }

    @Override
    public void start() throws Exception {
        getServer().start();
    }

    @Override
    public void stop() throws Exception {
        getServer().stop();
    }

}
