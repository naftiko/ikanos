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
package io.ikanos.engine.consumes.http;

import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientOperationSpec;
import io.ikanos.spec.consumes.http.HttpClientResourceSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Method;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HttpClientAdapterTest {

    @Test
    public void basicAuthenticationShouldSetIdentifierAndSecret() {
        HttpClientSpec spec = new HttpClientSpec("basic", "https://api.example.com", null);
        BasicAuthenticationSpec authentication = new BasicAuthenticationSpec();
        authentication.setType("basic");
        authentication.setUsername("{{username}}");
        authentication.setPassword("{{password}}".toCharArray());
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("username", "alice", "password", "secret")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("alice", clientRequest.getChallengeResponse().getIdentifier());
        // Previously this only asserted non-null, which let bug S2116 slip through:
        // calling toString() on a char[] returned the array identity (e.g. "[C@1a2b3c")
        // instead of resolving the {{password}} Mustache placeholder. Compare the actual
        // resolved secret to guard against that regression.
        assertEquals("secret",
                String.valueOf(clientRequest.getChallengeResponse().getSecret()));
    }

    @Test
    public void digestAuthenticationShouldSetIdentifierAndSecret() {
        HttpClientSpec spec = new HttpClientSpec("digest", "https://api.example.com", null);
        DigestAuthenticationSpec authentication = new DigestAuthenticationSpec();
        authentication.setType("digest");
        authentication.setUsername("digest-user");
        authentication.setPassword("digest-pass".toCharArray());
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("digest-user", clientRequest.getChallengeResponse().getIdentifier());
        // Previously this only asserted non-null, which let bug S2116 slip through:
        // calling toString() on a char[] returned the array identity (e.g. "[C@1a2b3c")
        // instead of the literal password. Compare the actual secret content to guard
        // against that regression.
        assertEquals("digest-pass",
                String.valueOf(clientRequest.getChallengeResponse().getSecret()));
    }

    @Test
    public void apiKeyQueryAuthenticationShouldAppendQueryParameter() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("api_key");
        authentication.setPlacement("query");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "abc123")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertEquals("https://api.example.com/v1/items?api_key=abc123",
                clientRequest.getResourceRef().toString());
    }

    @Test
    public void apiKeyHeaderAuthenticationShouldSetHeaderForNonReservedKey() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("X-Custom-Key");
        authentication.setPlacement("header");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "abc123")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertEquals("abc123", clientRequest.getHeaders().getFirstValue("X-Custom-Key"));
    }

    @Test
    public void apiKeyHeaderAuthenticationWithAuthorizationKeyShouldRouteThroughChallengeResponse() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("Authorization");
        authentication.setPlacement("header");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "SENTINEL-TOKEN-12345")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        // Authorization is a reserved header: Restlet silently drops it if added via
        // getHeaders().add() (see #698). It must go through the ChallengeResponse instead.
        assertEquals(null, clientRequest.getHeaders().getFirstValue("Authorization", true));
        assertNotNull(clientRequest.getChallengeResponse());

        // AuthenticatorUtils.formatResponse() is the exact method Restlet's engine calls to
        // serialize the ChallengeResponse into the outbound Authorization header value, so
        // this proves what will actually be sent on the wire, with no scheme prefix and no
        // leading space (asserting on the untrimmed value on purpose: a regression that
        // reintroduces the leading space injected by technicalName + " " must fail here).
        String wireValue = org.restlet.engine.security.AuthenticatorUtils.formatResponse(
                clientRequest.getChallengeResponse(), clientRequest,
                new org.restlet.util.Series<>(org.restlet.data.Header.class));
        assertEquals("SENTINEL-TOKEN-12345", wireValue);
    }

    @Test
    public void apiKeyHeaderAuthenticationWithLowercaseAuthorizationKeyShouldRouteThroughChallengeResponse() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("authorization");
        authentication.setPlacement("header");
        authentication.setValue("{{token}}");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of("token", "pk_live_abc")), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        // Untrimmed on purpose — see comment above.
        String wireValue = org.restlet.engine.security.AuthenticatorUtils.formatResponse(
                clientRequest.getChallengeResponse(), clientRequest,
                new org.restlet.util.Series<>(org.restlet.data.Header.class));
        assertEquals("pk_live_abc", wireValue);
    }

    @Test
    public void shouldForwardExistingServerChallengeResponseWhenClientAuthIsAbsent() {
        HttpClientSpec spec = new HttpClientSpec("forwarded", "https://api.example.com", null);
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()), spec);

        Request serverRequest = new Request(Method.GET, "https://upstream.example.com");
        ChallengeResponse upstreamChallenge = new ChallengeResponse(ChallengeScheme.HTTP_BASIC);
        upstreamChallenge.setIdentifier("bob");
        upstreamChallenge.setSecret("forwarded-secret".toCharArray());
        serverRequest.setChallengeResponse(upstreamChallenge);

        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");
        adapter.setChallengeResponse(serverRequest, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertNotNull(clientRequest.getChallengeResponse());
        assertEquals("bob", clientRequest.getChallengeResponse().getIdentifier());
        assertEquals("forwarded-secret",
                String.valueOf(clientRequest.getChallengeResponse().getSecret()));
    }

    /**
     * #840: the schema says placement defaults to {@code header}, and the exposed side reads it
     * that way. This test used to expect "Placement is required", which was the bug.
     */
    @Test
    public void setChallengeResponseShouldSendTheKeyAsAHeaderWhenApiKeyPlacementIsMissing() {
        HttpClientSpec spec = new HttpClientSpec("apikey", "https://api.example.com", null);
        ApiKeyAuthenticationSpec authentication = new ApiKeyAuthenticationSpec();
        authentication.setType("apikey");
        authentication.setKey("X-Dock-Key");
        authentication.setValue("MY_API_KEY");
        spec.setAuthentication(authentication);

        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()), spec);
        Request clientRequest = new Request(Method.GET, "https://api.example.com/v1/items");

        adapter.setChallengeResponse(null, clientRequest,
                clientRequest.getResourceRef().toString(), Map.of());

        assertEquals("MY_API_KEY", clientRequest.getHeaders().getFirstValue("X-Dock-Key"));
    }

    @Test
    public void prepareShouldApplyResourceLevelQueryParametersBetweenAdapterAndOperation()
            throws Exception {
        Request request = prepare("list-things", Map.of("filter", "a eq 1"));

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter&top=10"
                + "&res-query=from-resource&%24filter=a+eq+1&op-query=from-operation",
                request.getResourceRef().toString(),
                "#909: resource-level query parameters should be sent, after the adapter level"
                        + " and before the operation level");
    }

    @Test
    public void prepareShouldApplyResourceLevelHeader() throws Exception {
        Request request = prepare("list-things", Map.of("filter", "a eq 1"));

        assertEquals("from-resource", request.getHeaders().getFirstValue("X-Res-Header"),
                "#909: a resource-level header should be sent");
    }

    @Test
    public void prepareShouldLetOperationParameterOverrideBroaderParametersWithSameName()
            throws Exception {
        Request request = prepare("list-top", Map.of("filter", "a eq 1"));

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter"
                + "&%24filter=a+eq+1&res-query=from-operation&top=5",
                request.getResourceRef().toString(),
                "#909: an operation parameter with the same name and location as an adapter or"
                        + " resource parameter should replace it, not be sent a second time");
    }

    @Test
    public void prepareShouldApplyResourceLevelInputParametersToEveryOperation() throws Exception {
        Request request = prepare("list-other", Map.of("filter", "b eq 2"));

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter&top=10"
                + "&res-query=from-resource&%24filter=b+eq+2",
                request.getResourceRef().toString(),
                "#909: an operation without parameters should still send the resource's");
    }

    @Test
    public void prepareShouldResolveOverridingParameterAfterTheParametersItDependsOn()
            throws Exception {
        Request request = prepare("list-dependent", Map.of());

        assertEquals("https://probe.example/v1/things?adapter-query=from-adapter&top=10"
                + "&status=active&res-query=active",
                request.getResourceRef().toString(),
                "#909: an operation parameter that replaces a resource parameter should be"
                        + " resolved in its own position, after the parameters it uses");
    }

    @Test
    public void prepareShouldResolveBroaderParameterBeforeANarrowerOneReplacesWhatItUses()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                adapterDependencySpec());

        Request request = adapter.prepare("list", new HashMap<>()).getRequest();

        assertEquals("https://probe.example/v1/things?q=active&status=inactive",
                request.getResourceRef().toString(),
                "#909: parameters should be resolved in declaration order, as before the merge,"
                        + " so an adapter template still sees the adapter value it uses; only"
                        + " the replaced parameter should not be sent");
    }

    @Test
    public void prepareShouldSendTheCallerValueOfANarrowerParameterOverABroaderFixedValue()
            throws Exception {
        Request request = prepareDefaults(new HashMap<>(Map.of("top", "3")));

        assertEquals("https://probe.example/v1/things?top=3",
                request.getResourceRef().toString(),
                "#909: the caller value of a narrower parameter should not be overwritten by the"
                        + " fixed value of the broader parameter it replaces");
    }

    @Test
    public void prepareShouldSendTheBroaderFixedValueWhenTheCallerGivesNone() throws Exception {
        Request request = prepareDefaults(new HashMap<>());

        assertEquals("https://probe.example/v1/things?top=10",
                request.getResourceRef().toString());
    }

    @Test
    public void prepareShouldLetANarrowerHeaderReplaceAnAdapterHeaderWithAFixedValue()
            throws Exception {
        Request request = prepareDefaults(new HashMap<>());

        assertEquals("from-resource", request.getHeaders().getFirstValue("X-Mode"),
                "#909: setHeaders should not set an adapter header again once a narrower"
                        + " parameter has replaced it");
    }

    private Request prepareDefaults(Map<String, Object> arguments) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://probe.example/v1"
                      inputParameters:
                        X-Mode:
                          in: header
                          value: "from-adapter"
                      resources:
                        things:
                          path: "/things"
                          inputParameters:
                            top:
                              in: query
                              value: "10"
                            X-Mode:
                              in: header
                              value: "from-resource"
                          operations:
                            list:
                              method: GET
                              inputParameters:
                                top:
                                  in: query
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                (HttpClientSpec) spec.getCapability().getConsumes().get(0));
        return adapter.prepare("list", arguments).getRequest();
    }

    private Request prepare(String operation, Map<String, Object> arguments) throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                resourceParametersSpec());
        return adapter.prepare(operation, new HashMap<>(arguments)).getRequest();
    }

    private HttpClientSpec resourceParametersSpec() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://probe.example/v1"
                      inputParameters:
                        adapter-query:
                          in: query
                          value: "from-adapter"
                        top:
                          in: query
                          value: "10"
                      resources:
                        things:
                          path: "/things"
                          inputParameters:
                            res-query:
                              in: query
                              value: "from-resource"
                            X-Res-Header:
                              in: header
                              value: "from-resource"
                            filter:
                              in: query
                              wireName: "$filter"
                          operations:
                            list-things:
                              method: GET
                              inputParameters:
                                op-query:
                                  in: query
                                  value: "from-operation"
                            list-other:
                              method: GET
                            list-top:
                              method: GET
                              inputParameters:
                                res-query:
                                  in: query
                                  value: "from-operation"
                                top:
                                  in: query
                                  value: "5"
                            list-dependent:
                              method: GET
                              inputParameters:
                                status:
                                  in: query
                                  value: "active"
                                res-query:
                                  in: query
                                  value: "{{status}}"
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    private HttpClientSpec adapterDependencySpec() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://probe.example/v1"
                      inputParameters:
                        status:
                          in: query
                          value: "active"
                        q:
                          in: query
                          value: "{{status}}"
                      resources:
                        things:
                          path: "/things"
                          operations:
                            list:
                              method: GET
                              inputParameters:
                                status:
                                  in: query
                                  value: "inactive"
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    @Test
    public void prepareShouldPercentEncodeSlashesAndDotSegmentsInPathParameter() {
        HttpClientAdapter adapter = pagesAdapter();

        HttpInvocation invocation = adapter.prepare("trash-page",
                new java.util.HashMap<>(Map.of("page_id", "x/../../databases/DB-ID")));

        assertEquals("https://api.example.com/v1/pages/x%2F..%2F..%2Fdatabases%2FDB-ID",
                invocation.getRequest().getResourceRef().toString(),
                "A path parameter must stay one path segment (GHSA-rvg4-2rxw-mc64): '/' and '..'"
                        + " in a value must not reach another endpoint");
    }

    @Test
    public void prepareShouldPercentEncodeQueryAndFragmentDelimitersInPathParameter() {
        HttpClientAdapter adapter = pagesAdapter();

        HttpInvocation invocation = adapter.prepare("trash-page",
                new java.util.HashMap<>(Map.of("page_id", "U?x=1#frag")));

        assertEquals("https://api.example.com/v1/pages/U%3Fx=1%23frag",
                invocation.getRequest().getResourceRef().toString(),
                "'?' and '#' in a path parameter must not start a query string or a fragment");
    }

    @Test
    public void prepareShouldPercentEncodePercentSpaceAndNonAsciiInPathParameter() {
        HttpClientAdapter adapter = pagesAdapter();

        HttpInvocation invocation = adapter.prepare("trash-page",
                new java.util.HashMap<>(Map.of("page_id", "a%2Fb c\u00e9")));

        assertEquals("https://api.example.com/v1/pages/a%252Fb%20c%C3%A9",
                invocation.getRequest().getResourceRef().toString(),
                "An already-encoded '%2F' must not be decoded into a '/' by the upstream");
    }

    @Test
    public void prepareShouldKeepUnreservedAndSubDelimiterCharactersInPathParameter() {
        HttpClientAdapter adapter = pagesAdapter();

        HttpInvocation invocation = adapter.prepare("trash-page",
                new java.util.HashMap<>(Map.of("page_id", "Ab0-._~!$&'()*+,;=:@")));

        assertEquals("https://api.example.com/v1/pages/Ab0-._~!$&'()*+,;=:@",
                invocation.getRequest().getResourceRef().toString(),
                "Characters allowed in a path segment (RFC 3986 pchar) must be sent unchanged");
    }

    @Test
    public void prepareShouldRejectDotSegmentPathParameter() {
        HttpClientAdapter adapter = pagesAdapter();

        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("trash-page",
                        new java.util.HashMap<>(Map.of("page_id", ".."))),
                "A page_id of '..' must not reach the parent of /pages");
    }

    @Test
    public void prepareShouldRejectMissingPathParameterWhenOtherArgumentsArePresent() {
        HttpClientAdapter adapter = pagesAdapter();

        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("trash-page",
                        new java.util.HashMap<>(Map.of("other", "x"))),
                "A missing page_id must not send the request to /pages/");
    }

    @Test
    public void prepareShouldKeepTemplateLiteralPathAndBaseUriUnencoded() {
        HttpClientAdapter adapter = pagesAdapter();

        HttpInvocation invocation = adapter.prepare("trash-page",
                new java.util.HashMap<>(Map.of("page_id", "p1")));

        assertEquals("https://api.example.com/v1/pages/p1",
                invocation.getRequest().getResourceRef().toString());
    }

    @Test
    public void prepareShouldSubstituteUndeclaredPathVariablesWhenNoInputParameterIsDeclared()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                undeclaredPathSpec("https://api.example.com/v1"));

        Request request = adapter.prepare("get-invoice", new HashMap<>(
                Map.of("account_id", "acme", "invoice_id", "inv-1"))).getRequest();

        assertEquals("https://api.example.com/v1/accounts/acme/invoices/inv-1",
                request.getResourceRef().toString(),
                "#978: a {{name}} path variable takes the call parameter of the same name, with"
                        + " no inputParameters entry");
    }

    @Test
    public void prepareShouldPercentEncodeUndeclaredPathVariableAsOneSegment() throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                undeclaredPathSpec("https://api.example.com/v1"));

        Request request = adapter.prepare("get-invoice", new HashMap<>(
                Map.of("account_id", "a/../b", "invoice_id", "2026 #3"))).getRequest();

        assertEquals("https://api.example.com/v1/accounts/a%2F..%2Fb/invoices/2026%20%233",
                request.getResourceRef().toString(),
                "#978: an undeclared path variable is percent-encoded as one path segment, like a"
                        + " declared one (GHSA-rvg4-2rxw-mc64)");
    }

    @Test
    public void prepareShouldSubstituteUndeclaredPathVariableAfterABoundBaseUri()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(
                getMockCapability(Map.of("API_URL", "https://eu.api.example.com/v2")),
                undeclaredPathSpec("{{API_URL}}"));

        Request request = adapter.prepare("get-invoice", new HashMap<>(
                Map.of("account_id", "acme", "invoice_id", "a b"))).getRequest();

        assertEquals("https://eu.api.example.com/v2/accounts/acme/invoices/a%20b",
                request.getResourceRef().toString(),
                "#978: the baseUri comes from the bindings and the undeclared path variables of"
                        + " the resource path from the call");
    }

    @Test
    public void prepareShouldSubstituteTheValueOfAnExplicitPathParameter() throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                explicitPathValueSpec());

        Request request = adapter.prepare("get-invoice", new HashMap<>(
                Map.of("invoice_id", "inv-1"))).getRequest();

        assertEquals("https://api.example.com/v1/accounts/default/invoices/inv-1",
                request.getResourceRef().toString(),
                "#978: an explicit in: path entry can give the path variable a value");
    }

    @Test
    public void prepareShouldNotSendUndeclaredCallParameterAsQueryParameter() throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                undeclaredPathSpec("https://api.example.com/v1"));

        Request request = adapter.prepare("get-invoice", new HashMap<>(Map.of(
                "account_id", "acme", "invoice_id", "inv-1", "expand", "lines"))).getRequest();

        assertEquals("https://api.example.com/v1/accounts/acme/invoices/inv-1",
                request.getResourceRef().toString(),
                "#978: only path variables are inferred; a query parameter must be declared");
    }

    private HttpClientSpec undeclaredPathSpec(String baseUri) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: billing
                      baseUri: "%s"
                      resources:
                        invoice:
                          path: "/accounts/{{account_id}}/invoices/{{invoice_id}}"
                          operations:
                            get-invoice:
                              method: GET
                """.formatted(VersionHelper.getSchemaVersion(), baseUri);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    private HttpClientSpec explicitPathValueSpec() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: billing
                      baseUri: "https://api.example.com/v1"
                      resources:
                        invoice:
                          path: "/accounts/{{account_id}}/invoices/{{invoice_id}}"
                          inputParameters:
                            account_id:
                              in: path
                              value: "default"
                          operations:
                            get-invoice:
                              method: GET
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    @Test
    public void prepareShouldResolvePathValueFromAnEarlierQueryParameterWhenNoCallParameter()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        inputParameters:
                          version:
                            in: query
                            value: "v2"
                        resources:
                          pets:
                            path: "/{{api-version}}/pets"
                            inputParameters:
                              api-version:
                                in: path
                                value: "{{version}}"
                            operations:
                              list-pets:
                                method: GET
                        """));

        Request request = adapter.prepare("list-pets", new HashMap<>()).getRequest();

        assertEquals("https://api.example.com/v1/v2/pets?version=v2",
                request.getResourceRef().toString(),
                "#978: a path value is resolved in declaration order, so it can use an earlier"
                        + " query parameter, before the URI is rendered");
    }

    @Test
    public void prepareShouldKeepCallerPathValueWhenANarrowerDeclarationReplacesABroaderValue()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                replacedPathValueSpec());

        Request request = adapter.prepare("get-pet", new HashMap<>(Map.of("id", "42")))
                .getRequest();

        assertEquals("https://api.example.com/v1/pets/42", request.getResourceRef().toString(),
                "#978: a replaced broader value never overwrites the caller's value (#909)");
    }

    @Test
    public void prepareShouldUseBroaderPathValueAsDefaultWhenTheCallerSuppliesNone()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                replacedPathValueSpec());

        Request request = adapter.prepare("get-pet", new HashMap<>(Map.of("other", "x")))
                .getRequest();

        assertEquals("https://api.example.com/v1/pets/default",
                request.getResourceRef().toString(),
                "#978: a replaced broader value still acts as the default (#909)");
    }

    @Test
    public void prepareShouldResolveAPathValueTemplateOnlyOnce() throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        resources:
                          pets:
                            path: "/pets/{{id}}"
                            inputParameters:
                              id:
                                in: path
                                value: "prefix-{{id}}"
                            operations:
                              get-pet:
                                method: GET
                                inputParameters:
                                  ref:
                                    in: query
                                    value: "{{id}}"
                        """));

        Request request = adapter.prepare("get-pet", new HashMap<>(Map.of("id", "42")))
                .getRequest();

        assertEquals("https://api.example.com/v1/pets/prefix-42?ref=prefix-42",
                request.getResourceRef().toString(),
                "#978: the URI and the later query parameter see the same, once-resolved value");
    }

    @Test
    public void prepareShouldFailWhenAnExplicitPathValueCannotBeResolved() throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        resources:
                          pets:
                            path: "/pets/{{id}}"
                            inputParameters:
                              id:
                                in: path
                                value: "{{selected-id}}"
                            operations:
                              get-pet:
                                method: GET
                        """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("get-pet", new HashMap<>(Map.of("id", "42"))));

        assertEquals("Unresolved template parameters in path parameter 'id': [selected-id]",
                error.getMessage(),
                "#978: an explicit path value that cannot resolve must not fall back to the"
                        + " caller's value");
    }

    @Test
    public void prepareShouldFailWhenANestedPathValueReferenceResolvesToNothing()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        resources:
                          pets:
                            path: "/pets/{{id}}"
                            inputParameters:
                              id:
                                in: path
                                value: "{{selected.id}}"
                            operations:
                              get-pet:
                                method: DELETE
                        """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("get-pet",
                        new HashMap<>(Map.of("id", "42", "selected", Map.of()))));

        assertEquals("Unresolved template parameters in path parameter 'id': [selected.id]",
                error.getMessage(),
                "#978: a nested reference that resolves to nothing must not fall back to the"
                        + " caller's value");
    }

    @Test
    public void prepareShouldFailWhenAPathValueTemplateCannotBeRendered() throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        resources:
                          pets:
                            path: "/pets/{{id}}"
                            inputParameters:
                              id:
                                in: path
                                value: "pet-{{selected}}"
                            operations:
                              get-pet:
                                method: DELETE
                        """));
        IllegalStateException renderingError = new IllegalStateException("cannot render");
        Object unrenderable = new Object() {
            @Override
            public String toString() {
                throw renderingError;
            }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("get-pet",
                        new HashMap<>(Map.of("id", "42", "selected", unrenderable))));

        assertEquals("Unresolved template parameters in path parameter 'id': rendering failed",
                error.getMessage(),
                "#978: a path value that fails to render must not fall back to the caller's"
                        + " value");
    }

    @Test
    public void prepareShouldIgnoreUnresolvableReplacedPathValueWhenTheCallerSuppliesOne()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        resources:
                          pets:
                            path: "/pets/{{id}}"
                            inputParameters:
                              id:
                                in: path
                                value: "{{selected-id}}"
                            operations:
                              get-pet:
                                method: GET
                                inputParameters:
                                  id:
                                    in: path
                        """));

        Request request = adapter.prepare("get-pet", new HashMap<>(Map.of("id", "42")))
                .getRequest();

        assertEquals("https://api.example.com/v1/pets/42", request.getResourceRef().toString(),
                "#978: a replaced broader path value is only a default (#909)");
    }

    @Test
    public void prepareShouldNotLetAQueryParameterOfTheSameNameChangeTheCallerPathValue()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                sameNameQuerySpec(""));

        Request request = adapter.prepare("get-pet", new HashMap<>(Map.of("id", "42")))
                .getRequest();

        assertEquals("https://api.example.com/v1/pets/42?id=all",
                request.getResourceRef().toString(),
                "#978: the URI only sees call parameters and in: path values");
    }

    @Test
    public void prepareShouldNotLetAQueryParameterOfTheSameNameChangeAnExplicitPathValue()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                sameNameQuerySpec("value: \"7\""));

        Request request = adapter.prepare("get-pet", new HashMap<>(Map.of("id", "42")))
                .getRequest();

        assertEquals("https://api.example.com/v1/pets/7?id=all",
                request.getResourceRef().toString(),
                "#978: a query parameter of the same name never replaces an in: path value");
    }

    /** Resource {@code id: in: path} (plus {@code pathExtra}), operation {@code id: in: query}. */
    private HttpClientSpec sameNameQuerySpec(String pathExtra) throws Exception {
        return consumedSpec("""
                resources:
                  pets:
                    path: "/pets/{{id}}"
                    inputParameters:
                      id:
                        in: path
                        %s
                    operations:
                      get-pet:
                        method: GET
                        inputParameters:
                          id:
                            in: query
                            value: "all"
                """.formatted(pathExtra));
    }

    @Test
    public void prepareShouldRejectMissingPathVariableWhenOnlyAConstantHeaderIsDeclared()
            throws Exception {
        HttpClientAdapter adapter = new HttpClientAdapter(getMockCapability(Map.of()),
                consumedSpec("""
                        inputParameters:
                          Accept:
                            in: header
                            value: "application/json"
                        resources:
                          pets:
                            path: "/pets/{{id}}"
                            operations:
                              get-pet:
                                method: GET
                        """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare("get-pet", new HashMap<>()));

        assertEquals("Unresolved template parameters in URI: [id]", error.getMessage(),
                "#858: a declared constant must not hide a path variable the call did not supply");
    }

    private HttpClientSpec replacedPathValueSpec() throws Exception {
        return consumedSpec("""
                resources:
                  pets:
                    path: "/pets/{{id}}"
                    inputParameters:
                      id:
                        in: path
                        value: "default"
                    operations:
                      get-pet:
                        method: GET
                        inputParameters:
                          id:
                            in: path
                """);
    }

    /** An http adapter {@code pets} on {@code https://api.example.com/v1}; body is indented. */
    private HttpClientSpec consumedSpec(String body) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: pets
                      baseUri: "https://api.example.com/v1"
                """.formatted(VersionHelper.getSchemaVersion())
                + body.indent(6);
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        return (HttpClientSpec) spec.getCapability().getConsumes().get(0);
    }

    private HttpClientAdapter pagesAdapter() {
        HttpClientSpec spec = new HttpClientSpec("notion", "https://api.example.com/v1", null);
        HttpClientResourceSpec resource = new HttpClientResourceSpec("/pages/{{page_id}}",
                "page", null);
        HttpClientOperationSpec operation =
                new HttpClientOperationSpec(resource, "PATCH", "trash-page", null);
        resource.setOperations(new java.util.LinkedHashMap<>(Map.of("trash-page", operation)));
        spec.getResources().put("page", resource);
        return new HttpClientAdapter(getMockCapability(Map.of()), spec);
    }

    private Capability getMockCapability(Map<String, Object> bindings) {
        Capability capability = mock(Capability.class);
        when(capability.getBindings()).thenReturn(bindings);
        return capability;
    }
}
