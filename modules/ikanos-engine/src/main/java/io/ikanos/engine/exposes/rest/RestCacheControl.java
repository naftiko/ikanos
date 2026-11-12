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

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.Dimension;
import org.restlet.data.Method;
import io.ikanos.engine.exposes.CacheScopes;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

/**
 * {@code Cache-Control} on REST answers, from the same {@code ttlMs} and {@code cacheScope} the MCP
 * adapter sends as cache hints (#1021).
 *
 * <p>Only a successful {@code GET} answer of a declared operation gets one, and only when a
 * {@code ttlMs} is set on the operation or the adapter, so capabilities that set none are
 * unchanged. A {@code forward} answer never gets one: the resource is a transparent proxy, and the
 * upstream decides, since the caller's credentials may be passed to it. A TTL of 0 sends
 * {@code no-store}; a positive one sends {@code public} or {@code private} with {@code max-age} in
 * whole seconds, rounded up. A {@code private} answer also sends {@code Vary: Authorization}, and an
 * answer that reads an input from outside the URL sends {@code Vary: *}, since that input is not
 * part of the cache key and Restlet cannot name arbitrary headers in {@code Vary}.</p>
 */
final class RestCacheControl {

    /** Where an input is part of the URL, and so of the cache key. */
    static final Set<String> URL_LOCATIONS = Set.of("path", "query");

    private RestCacheControl() {
        // Utility class — no instances.
    }

    /**
     * Add {@code Cache-Control} to {@code response} when it applies.
     *
     * @param operation the operation that answered, or {@code null} for a forward, which gets none
     */
    static void apply(Request request, Response response, RestServerSpec server,
            RestServerResourceSpec resource, RestServerOperationSpec operation) {
        if (operation == null) {
            return;
        }
        Method method = request.getMethod();
        if (!Method.GET.equals(method) && !Method.HEAD.equals(method)) {
            return;
        }
        if (response.getStatus() == null || !response.getStatus().isSuccess()) {
            return;
        }
        Integer ttlMs = ttlMs(server, operation);
        if (ttlMs == null) {
            return;
        }
        List<CacheDirective> directives = response.getCacheDirectives();
        directives.clear();
        if (ttlMs == 0) {
            directives.add(CacheDirective.noStore());
            return;
        }
        String scope = scopeDecision(server, resource, operation).scope();
        directives.add(CacheScopes.PUBLIC.equals(scope)
                ? CacheDirective.publicInfo() : CacheDirective.privateInfo());
        // Rounded up, so a TTL under a second never becomes max-age=0 ("revalidate")
        directives.add(CacheDirective.maxAge((int) Math.ceil(ttlMs / 1000.0)));
        if (CacheScopes.PRIVATE.equals(scope)) {
            response.getDimensions().add(Dimension.AUTHORIZATION);
        }
        if (inputOutsideUrl(server, resource, operation) != null) {
            response.getDimensions().add(Dimension.UNSPECIFIED);
        }
    }

    /** The operation's {@code ttlMs}, else the adapter's, else {@code null}. */
    static Integer ttlMs(RestServerSpec server, RestServerOperationSpec operation) {
        return operation != null && operation.getTtlMs() != null
                ? operation.getTtlMs() : server.getTtlMs();
    }

    /** The scope for answers of {@code operation} on {@code resource}, and why. */
    static CacheScopes.Decision scopeDecision(RestServerSpec server,
            RestServerResourceSpec resource, RestServerOperationSpec operation) {
        String declared = operation != null && operation.getCacheScope() != null
                ? operation.getCacheScope() : server.getCacheScope();
        return CacheScopes.resolve(declared != null ? declared : CacheScopes.PRIVATE,
                callerDependence(server, resource, operation));
    }

    /**
     * Why answers of {@code operation} can depend on the caller, or {@code null}. The URL is the
     * cache key, so path and query inputs are not caller-dependent; credentials and inputs read
     * from a header or the body are.
     */
    static String callerDependence(RestServerSpec server, RestServerResourceSpec resource,
            RestServerOperationSpec operation) {
        if (server.getAuthentication() != null) {
            return "the adapter authenticates its callers";
        }
        String input = inputOutsideUrl(server, resource, operation);
        return input != null ? "input '" + input + "' is read from outside the URL" : null;
    }

    /**
     * The name of the first input read from outside the URL (a header, or the body, which is where
     * an input without {@code in} is read), or {@code null}.
     */
    static String inputOutsideUrl(RestServerSpec server, RestServerResourceSpec resource,
            RestServerOperationSpec operation) {
        return Stream.of(server.getInputParameters(),
                resource != null ? resource.getInputParameters() : List.<InputParameterSpec>of(),
                operation != null ? operation.getInputParameters() : List.<InputParameterSpec>of())
                .filter(list -> list != null)
                .flatMap(List::stream)
                .filter(p -> p.getIn() == null
                        || !URL_LOCATIONS.contains(p.getIn().toLowerCase(Locale.ROOT)))
                .map(InputParameterSpec::getName)
                .findFirst().orElse(null);
    }

    /**
     * Check every {@code ttlMs} and {@code cacheScope} of the adapter, so an invalid value fails at
     * startup rather than on a request. The engine does not validate the schema at load time.
     */
    static void validate(RestServerSpec server) {
        check(server.getTtlMs(), server.getCacheScope(), "adapter " + server.getNamespace());
        for (RestServerResourceSpec resource : server.getResources().values()) {
            for (RestServerOperationSpec operation : resource.getOperations().values()) {
                check(operation.getTtlMs(), operation.getCacheScope(),
                        "operation " + operation.getName());
            }
        }
    }

    private static void check(Integer ttlMs, String cacheScope, String where) {
        if (ttlMs != null && ttlMs < 0) {
            throw new IllegalArgumentException(
                    "REST ttlMs must be zero or more on " + where + ", got " + ttlMs);
        }
        if (cacheScope != null && !Set.of(CacheScopes.PUBLIC, CacheScopes.PRIVATE, CacheScopes.AUTO)
                .contains(cacheScope)) {
            throw new IllegalArgumentException("REST cacheScope must be public, private or auto"
                    + " on " + where + ", got '" + cacheScope + "'");
        }
    }
}
