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
}
