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

import java.util.Locale;
import java.util.Set;
import org.restlet.Restlet;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.restlet.routing.TemplateRoute;
import org.restlet.routing.Variable;
import io.ikanos.Capability;
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.rest.IdentityHeaderSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * Implementation of the ServerAdapter abstract class that sets up an HTTP server using the Restlet
 * Framework acting as a spec-driven API server.
 */
public class RestServerAdapter extends ServerAdapter {

    private final Router router;
    private final RestInputRequests inputRequests;

    public RestServerAdapter(Capability capability, RestServerSpec serverSpec) {
        super(capability, serverSpec);
        AccessControl.checkAtStartup(serverSpec, false, AccessControl.declaredIn(serverSpec));
        requireValidIdentityHeaders(serverSpec);
        this.router = new Router();
        RestCacheControl.validate(serverSpec);
        this.inputRequests = new RestInputRequests(capability, serverSpec.getNamespace(),
                serverSpec.getPath(), () -> false);

        // Answers to input requests, before the declared resources so none can shadow them.
        TemplateRoute answers = getRouter().attach(RestInputRequests.PREFIX + "{handle}",
                inputRequests.answerResource(), Template.MODE_EQUALS);
        // Any characters: a URI segment's regex recurses on each one, and a handle of a few KiB
        // overflows a listener thread's stack, which Restlet answers 404. A handle that is no
        // sealed state fails to open and answers 410.
        answers.getTemplate().getVariables().put("handle", new Variable(Variable.TYPE_ALL));

        for (RestServerResourceSpec res : getRestServerSpec().getResources().values()) {
            if (res.getPath() != null && res.getPath().startsWith("/_ikanos/")) {
                throw new IllegalArgumentException("Resource path '" + res.getPath()
                        + "' is under /_ikanos/, which the REST adapter reserves for its own "
                        + "resources");
            }
            String pathTemplate = toUriTemplate(res.getPath());
            ResourceRestlet resourceRestlet =
                    new ResourceRestlet(capability, serverSpec, res, inputRequests);
            inputRequests.register(res.getPath(), resourceRestlet);
            TemplateRoute route = getRouter().attach(pathTemplate, resourceRestlet);
            route.getTemplate().getVariables().put("path", new Variable(Variable.TYPE_URI_PATH));
        }

        initServer(serverSpec.getAddress(), serverSpec.getPort(),
                buildServerChain(limitRequestsInProgress(this.router)));
    }

    /**
     * {@code forward.identityHeaders} (#998) need the validated token of
     * {@code authentication: oauth2}, a claim, a known {@code ifMissing} and a custom header name.
     */
    private static void requireValidIdentityHeaders(RestServerSpec serverSpec) {
        String adapter = "REST adapter '" + serverSpec.getNamespace() + "'";
        boolean oauth2 = serverSpec.getAuthentication() instanceof OAuth2AuthenticationSpec;
        serverSpec.getResources().forEach((resourceName, resource) -> {
            if (resource.getForward() != null
                    && !resource.getForward().getIdentityHeaders().isEmpty()) {
                String where = adapter + ", resource '" + resourceName + "'";
                if (!oauth2) {
                    throw new IllegalArgumentException(where + " declares forward.identityHeaders,"
                            + " which need 'authentication: oauth2' on the adapter");
                }
                resource.getForward().getIdentityHeaders().forEach(
                        (header, identity) -> requireValidIdentityHeader(where, header, identity));
            }
        });
    }

    /**
     * Headers the HTTP connector sets from its own request model: a value set for them as a
     * custom header is dropped, so an identity header with one of these names would never be sent.
     */
    static final Set<String> CONNECTOR_HEADERS = Set.of("accept", "accept-charset",
            "accept-encoding", "accept-language", "accept-patch", "accept-ranges",
            "access-control-allow-credentials", "access-control-allow-headers",
            "access-control-allow-methods", "access-control-allow-origin",
            "access-control-expose-headers", "access-control-max-age",
            "access-control-request-headers", "access-control-request-method", "age", "allow",
            "authentication-info", "authorization", "cache-control", "connection",
            "content-disposition", "content-encoding", "content-language", "content-length",
            "content-location", "content-md5", "content-range", "content-type", "cookie", "date",
            "etag", "expect", "expires", "from", "host", "if-match", "if-modified-since",
            "if-none-match", "if-range", "if-unmodified-since", "last-modified", "location",
            "max-forwards", "proxy-authenticate", "proxy-authorization", "range", "referer",
            "retry-after", "server", "set-cookie", "set-cookie2", "user-agent", "vary", "via",
            "warning", "www-authenticate", "keep-alive", "pragma", "proxy-connection", "te",
            "trailer", "transfer-encoding", "upgrade");

    /** One {@code forward.identityHeaders} entry: a claim, a known ifMissing, a custom header. */
    static void requireValidIdentityHeader(String where, String header,
            IdentityHeaderSpec identity) {
        String entry = where + ", identity header '" + header + "'";
        if (identity == null || identity.getClaim() == null || identity.getClaim().isBlank()) {
            throw new IllegalArgumentException(entry + " names no claim");
        }
        String ifMissing = identity.getIfMissing();
        if (ifMissing != null && !IdentityHeaderSpec.IF_MISSING_FAIL.equals(ifMissing)
                && !IdentityHeaderSpec.IF_MISSING_OMIT.equals(ifMissing)) {
            throw new IllegalArgumentException(entry + ": ifMissing must be 'fail' or 'omit'");
        }
        if (header == null || CONNECTOR_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(entry + " is a standard header the HTTP connector"
                    + " sets itself, so it could not be sent. Use a custom header name");
        }
    }

    public RestServerSpec getRestServerSpec() {
        return (RestServerSpec) getSpec();
    }

    public Router getRouter() {
        return router;
    }

}
