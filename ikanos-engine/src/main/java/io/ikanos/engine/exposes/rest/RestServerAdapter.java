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
package io.ikanos.engine.exposes.rest;

import org.restlet.Restlet;
import org.restlet.routing.Router;
import org.restlet.routing.TemplateRoute;
import org.restlet.routing.Variable;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Implementation of the ServerAdapter abstract class that sets up an HTTP server using the Restlet
 * Framework acting as a spec-driven API server.
 */
public class RestServerAdapter extends ServerAdapter {

    private final Router router;

    public RestServerAdapter(Capability capability, RestServerSpec serverSpec) {
        super(capability, serverSpec);
        this.router = new Router();

        for (RestServerResourceSpec res : getRestServerSpec().getResources()) {
            String pathTemplate = toUriTemplate(res.getPath());
            Restlet resourceRestlet = new ResourceRestlet(capability, serverSpec, res);
            TemplateRoute route = getRouter().attach(pathTemplate, resourceRestlet);
            route.getTemplate().getVariables().put("path", new Variable(Variable.TYPE_URI_PATH));
        }

        initServer(serverSpec.getAddress(), serverSpec.getPort(),
                buildServerChain(this.router));
    }

    public RestServerSpec getRestServerSpec() {
        return (RestServerSpec) getSpec();
    }

    public Router getRouter() {
        return router;
    }

}
