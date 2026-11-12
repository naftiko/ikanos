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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Dimension;
import org.restlet.data.Method;
import org.restlet.data.Status;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.exposes.rest.RestServerForwardSpec;
import io.ikanos.spec.exposes.rest.RestServerOperationSpec;
import io.ikanos.spec.exposes.rest.RestServerResourceSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;

class RestCacheControlTest {

    @Test
    void applyShouldSendPublicMaxAgeOnAGetWhenTheAdapterSetsATtl() {
        RestServerSpec server = server(60_000, "public");

        Response response = apply(Method.GET, Status.SUCCESS_OK, server, resource(), null);

        assertThat(cacheControl(response)).isEqualTo("public, max-age=60");
        assertThat(response.getDimensions()).doesNotContain(Dimension.AUTHORIZATION);
    }

    @Test
    void applyShouldSendNoStoreWhenTheTtlIsZero() {
        Response response = apply(Method.GET, Status.SUCCESS_OK, server(0, "public"),
                resource(), null);

        assertThat(cacheControl(response)).isEqualTo("no-store");
    }

    @Test
    void applyShouldSendPrivateAndVaryOnAuthorizationWhenTheScopeIsPrivate() {
        Response response = apply(Method.HEAD, Status.SUCCESS_OK, server(5_000, null),
                resource(), null);

        assertThat(cacheControl(response)).isEqualTo("private, max-age=5");
        assertThat(response.getDimensions()).contains(Dimension.AUTHORIZATION);
    }

    @Test
    void applyShouldLetTheOperationOverrideTheAdapter() {
        RestServerOperationSpec operation = new RestServerOperationSpec();
        operation.setTtlMs(1_000);
        operation.setCacheScope("private");

        Response response = apply(Method.GET, Status.SUCCESS_OK, server(60_000, "public"),
                resource(), operation);

        assertThat(cacheControl(response)).isEqualTo("private, max-age=1");
    }

    @Test
    void applyShouldSendNothingWhenNoTtlIsSet() {
        Response response = apply(Method.GET, Status.SUCCESS_OK, server(null, "public"),
                resource(), null);

        assertThat(response.getCacheDirectives()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void applyShouldSendNothingOnAWrite(String method) {
        Response response = apply(Method.valueOf(method), Status.SUCCESS_OK,
                server(60_000, "public"), resource(), null);

        assertThat(response.getCacheDirectives()).isEmpty();
    }

    @Test
    void applyShouldSendNothingOnAnError() {
        Response response = apply(Method.GET, Status.SERVER_ERROR_BAD_GATEWAY,
                server(60_000, "public"), resource(), null);

        assertThat(response.getCacheDirectives()).isEmpty();
    }

    @Test
    void scopeDecisionShouldMakeAutoPublicWhenNothingDependsOnTheCaller() {
        assertThat(RestCacheControl.scopeDecision(server(1_000, "auto"), resource(), null).scope())
                .isEqualTo("public");
    }

    @Test
    void scopeDecisionShouldMakeAutoPrivateWhenTheAdapterAuthenticates() {
        RestServerSpec server = server(1_000, "auto");
        server.setAuthentication(new BearerAuthenticationSpec("{{TOKEN}}"));

        assertThat(RestCacheControl.scopeDecision(server, resource(), null).scope())
                .isEqualTo("private");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "cookie", "Proxy-Authorization"})
    void scopeDecisionShouldMakeAutoPrivateWhenAForwardPassesCallerCredentials(String header) {
        RestServerForwardSpec forward = new RestServerForwardSpec("upstream");
        forward.getTrustedHeaders().add(header);
        RestServerResourceSpec resource = new RestServerResourceSpec("/proxy", "proxy", null,
                null, forward);

        assertThat(RestCacheControl.scopeDecision(server(1_000, "auto"), resource, null).scope())
                .isEqualTo("private");
    }

    @Test
    void scopeDecisionShouldKeepAutoPublicWhenAForwardPassesOnlyNonCredentialHeaders() {
        RestServerForwardSpec forward = new RestServerForwardSpec("upstream");
        forward.getTrustedHeaders().add("X-Correlation-Id");
        RestServerResourceSpec resource = new RestServerResourceSpec("/proxy", "proxy", null,
                null, forward);

        assertThat(RestCacheControl.scopeDecision(server(1_000, "auto"), resource, null).scope())
                .isEqualTo("public");
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "cookie"})
    void scopeDecisionShouldMakeAutoPrivateWhenAnInputComesFromTheRequestHeaders(String in) {
        RestServerOperationSpec operation = new RestServerOperationSpec();
        operation.setInputParameters(
                Map.of("tenant", new InputParameterSpec("tenant", "string", in, null)));

        assertThat(RestCacheControl.scopeDecision(server(1_000, "auto"), resource(), operation)
                .scope()).isEqualTo("private");
    }

    @Test
    void scopeDecisionShouldKeepAutoPublicWhenInputsComeFromTheUrl() {
        RestServerOperationSpec operation = new RestServerOperationSpec();
        operation.setInputParameters(Map.of("id", new InputParameterSpec("id", "string", "path", null),
                "q", new InputParameterSpec("q", "string", "query", null)));

        assertThat(RestCacheControl.scopeDecision(server(1_000, "auto"), resource(), operation)
                .scope()).isEqualTo("public");
    }

    @Test
    void validateShouldRejectANegativeTtl() {
        assertThatThrownBy(() -> RestCacheControl.validate(server(-1, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ttlMs");
    }

    @Test
    void validateShouldRejectAnUnknownScope() {
        assertThatThrownBy(() -> RestCacheControl.validate(server(1_000, "shared")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cacheScope");
    }

    static Response apply(Method method, Status status, RestServerSpec server,
            RestServerResourceSpec resource, RestServerOperationSpec operation) {
        Request request = new Request(method, "http://localhost/things");
        Response response = new Response(request);
        response.setStatus(status);
        RestCacheControl.apply(request, response, server, resource, operation);
        return response;
    }

    static String cacheControl(Response response) {
        return response.getCacheDirectives().stream()
                .map(d -> d.getValue() != null ? d.getName() + "=" + d.getValue() : d.getName())
                .collect(Collectors.joining(", "));
    }

    static RestServerSpec server(Integer ttlMs, String cacheScope) {
        RestServerSpec server = new RestServerSpec(null, 8080, "demo");
        server.setTtlMs(ttlMs);
        server.setCacheScope(cacheScope);
        return server;
    }

    static RestServerResourceSpec resource() {
        return new RestServerResourceSpec("/things");
    }
}
