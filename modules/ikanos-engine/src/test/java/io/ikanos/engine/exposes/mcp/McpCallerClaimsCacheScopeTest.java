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
package io.ikanos.engine.exposes.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * #998: a resource whose call reads the caller's claims depends on the caller, which its request
 * does not show, so it may not be advertised as cacheable by a shared cache.
 */
class McpCallerClaimsCacheScopeTest {

    /**
     * A capability whose {@code profile} resource calls {@code shop.get-profile}.
     *
     * @param serverScope the adapter's cacheScope line, or ""
     * @param resourceLines extra lines of the resource, such as its cacheScope or with, the
     *        first unindented and the next ones indented by eight spaces
     * @param consumedHeader the value of the consumed adapter's X-User-Id header
     */
    private static Capability load(String serverScope, String resourceLines,
            String consumedHeader) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 0
                    namespace: profiles
                    description: "Profiles"
                    %s
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      profile:
                        display: "Profile"
                        uri: "data://profile"
                        description: "The caller's profile"
                        call: shop.get-profile
                        %s
                  consumes:
                  - type: http
                    namespace: shop
                    description: "Mentions auth and claims in plain text only"
                    baseUri: "http://127.0.0.1:1"
                    inputParameters:
                      X-User-Id:
                        in: header
                        value: "%s"
                    resources:
                      profile:
                        path: "/profile"
                        operations:
                          get-profile:
                            method: GET
                """.formatted(VersionHelper.getSchemaVersion(), serverScope, resourceLines,
                consumedHeader);
        return new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
    }

    private static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void loadShouldRefuseAPublicResourceWhoseConsumedCallReadsAClaim() {
        Exception error = assertThrows(Exception.class, () -> load("",
                "cacheScope: public", "{{auth.claims.sub}}"));

        assertTrue(messages(error).contains("MCP resource 'profile': cacheScope public"),
                messages(error));
    }

    @Test
    void loadShouldRefuseAResourceThatInheritsAPublicScopeAndReadsAClaim() {
        Exception error = assertThrows(Exception.class, () -> load("cacheScope: public", "",
                "{{auth.claims.sub}}"));

        assertTrue(messages(error).contains("cacheScope public"), messages(error));
    }

    @Test
    void loadShouldRefuseAPublicResourceWhoseWithReadsAClaim() {
        Exception error = assertThrows(Exception.class, () -> load("", "cacheScope: public\n        with:\n"
                + "          tenant: \"{{auth.claims.tenant}}\"", "static"));

        assertTrue(messages(error).contains("cacheScope public"), messages(error));
    }

    @Test
    void loadShouldAcceptAPrivateResourceThatReadsAClaim() {
        assertDoesNotThrow(() -> load("cacheScope: public",
                "cacheScope: private", "{{auth.claims.sub}}"));
    }

    @Test
    void loadShouldAcceptAPublicResourceThatReadsNoClaim() {
        // The consumed adapter's description names auth and claims, but no template reads them
        assertDoesNotThrow(() -> load("", "cacheScope: public", "static"));
    }

    /**
     * A capability whose public {@code catalogue} resource has a step calling {@code cat.list},
     * which calls {@code listTarget}. {@code cat.other}, a sibling flow nothing calls, sends
     * {@code siblingWith}; {@code inner.deep} sends {@code deepWith} to {@code shop.list}; the
     * {@code people} adapter sends {@code peopleHeader}.
     */
    private static Capability loadAggregate(String listTarget, String siblingWith,
            String deepWith, String peopleHeader) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 0
                    namespace: catalogue
                    description: "Catalogue"
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      catalogue:
                        display: "Catalogue"
                        uri: "data://catalogue"
                        description: "The catalogue"
                        cacheScope: public
                        steps:
                          list:
                            type: call
                            call: cat.list
                  aggregates:
                  - namespace: cat
                    flows:
                      list:
                        description: "Lists the catalogue"
                        steps:
                          fetch:
                            type: call
                            call: %s
                      other:
                        description: "A sibling flow"
                        steps:
                          fetch:
                            type: call
                            call: shop.list
                            with:
                              user: "%s"
                  - namespace: inner
                    flows:
                      deep:
                        description: "Called by another flow"
                        steps:
                          fetch:
                            type: call
                            call: shop.list
                            with:
                              user: "%s"
                  consumes:
                  - type: http
                    namespace: shop
                    description: "The shop"
                    baseUri: "http://127.0.0.1:1"
                    resources:
                      items:
                        path: "/items"
                        operations:
                          list:
                            method: GET
                  - type: http
                    namespace: people
                    description: "Profiles, for tools"
                    baseUri: "http://127.0.0.1:1"
                    inputParameters:
                      X-User-Id:
                        in: header
                        value: "%s"
                    resources:
                      profile:
                        path: "/profile"
                        operations:
                          get-profile:
                            method: GET
                """.formatted(VersionHelper.getSchemaVersion(), listTarget, siblingWith,
                deepWith, peopleHeader);
        return new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
    }

    private static final String CLAIM = "{{auth.claims.sub}}";

    @Test
    void loadShouldAcceptAPublicResourceWhenOnlyAnUnrelatedAdapterReadsAClaim() {
        assertDoesNotThrow(() -> loadAggregate("shop.list", "static", "static", CLAIM));
    }

    @Test
    void loadShouldAcceptAPublicResourceWhenOnlyASiblingFlowReadsAClaim() {
        assertDoesNotThrow(() -> loadAggregate("shop.list", CLAIM, "static", "static"));
    }

    @Test
    void loadShouldRefuseAPublicResourceWhoseFlowCallsAFlowThatReadsAClaim() {
        Exception error = assertThrows(Exception.class,
                () -> loadAggregate("inner.deep", "static", CLAIM, "static"));

        assertTrue(messages(error).contains("MCP resource 'catalogue': cacheScope public"),
                messages(error));
    }

    @Test
    void loadShouldRefuseAPublicResourceWhoseFlowCallsAnAdapterThatReadsAClaim() {
        Exception error = assertThrows(Exception.class,
                () -> loadAggregate("people.get-profile", "static", "static", CLAIM));

        assertTrue(messages(error).contains("cacheScope public"), messages(error));
    }

    @Test
    void loadShouldAcceptAPublicResourceWhoseFlowsReadNoClaim() {
        assertDoesNotThrow(() -> loadAggregate("inner.deep", "static", "static", "static"));
    }

    /** A public {@code report} resource whose consumed body has {@code $onlyIf: <condition>}. */
    private static Capability loadBodyCondition(String condition) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 0
                    namespace: reports
                    description: "Reports"
                    tools:
                      hello:
                        description: "Say hello"
                        outputParameters:
                          msg:
                            type: string
                            value: "hi"
                    resources:
                      report:
                        display: "Report"
                        uri: "data://report"
                        description: "The report"
                        cacheScope: public
                        call: shop.search
                  consumes:
                  - type: http
                    namespace: shop
                    baseUri: "http://127.0.0.1:1"
                    resources:
                      search:
                        path: "/search"
                        operations:
                          search:
                            method: POST
                            body:
                              type: json
                              data:
                                internal: {$onlyIf: "%s", include: true}
                """.formatted(VersionHelper.getSchemaVersion(), condition);
        return new Capability(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class));
    }

    @Test
    void loadShouldRefuseAPublicResourceWhoseConsumedBodyOnlyIfReadsAClaim() {
        // #1132: an $onlyIf names its input without braces, yet the body depends on the claim
        Exception error = assertThrows(Exception.class,
                () -> loadBodyCondition("auth.claims.admin"));

        assertTrue(messages(error).contains("MCP resource 'report': cacheScope public"),
                messages(error));
    }

    @Test
    void loadShouldAcceptAPublicResourceWhoseConsumedBodyOnlyIfReadsNoClaim() {
        assertDoesNotThrow(() -> loadBodyCondition("verbose"));
    }
}
