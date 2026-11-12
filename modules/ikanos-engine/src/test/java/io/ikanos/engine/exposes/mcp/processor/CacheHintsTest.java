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
package io.ikanos.engine.exposes.mcp.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpTrustSpec;

class CacheHintsTest {

    @Test
    void forServerShouldDefaultToZeroAndPrivate() {
        assertThat(CacheHints.forServer(new McpServerSpec()))
                .isEqualTo(new CacheHints(0, "private"));
    }

    @Test
    void forResourceShouldFallBackToTheServerFieldByField() {
        McpServerSpec server = new McpServerSpec();
        server.setTtlMs(60000);
        server.setCacheScope("public");
        McpServerResourceSpec resource = new McpServerResourceSpec();
        resource.setTtlMs(1000);

        assertThat(CacheHints.forResource(server, resource))
                .isEqualTo(new CacheHints(1000, "public"));
    }

    @Test
    void forResourceShouldUseTheServerHintsWhenNoResourceMatched() {
        McpServerSpec server = new McpServerSpec();
        server.setTtlMs(60000);

        assertThat(CacheHints.forResource(server, null)).isEqualTo(new CacheHints(60000, "private"));
    }

    @Test
    void forResourceShouldRejectAnUnknownResourceScope() {
        McpServerResourceSpec resource = new McpServerResourceSpec();
        resource.setCacheScope("shared");

        assertThatThrownBy(() -> CacheHints.forResource(new McpServerSpec(), resource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheScope");
    }

    @Test
    void forServerShouldMakeAutoPublicWhenTheAdapterDoesNotAuthenticate() {
        McpServerSpec server = new McpServerSpec();
        server.setCacheScope("auto");

        assertThat(CacheHints.forServer(server).cacheScope()).isEqualTo("public");
    }

    @Test
    void forServerShouldMakeAutoPrivateWhenTheAdapterAuthenticatesItsCallers() {
        McpServerSpec server = new McpServerSpec();
        server.setCacheScope("auto");
        server.setAuthentication(new BearerAuthenticationSpec("{{TOKEN}}"));

        assertThat(CacheHints.forServer(server).cacheScope()).isEqualTo("private");
        assertThat(CacheHints.scopeDecision(server, null).reason())
                .contains("authenticates its callers");
    }

    @Test
    void forServerShouldMakeAutoPrivateWhenCallersAreIdentifiedThroughTrust() {
        McpServerSpec server = new McpServerSpec();
        server.setCacheScope("auto");
        server.setTrust(new McpTrustSpec("forwarded"));

        assertThat(CacheHints.forServer(server).cacheScope()).isEqualTo("private");
        assertThat(CacheHints.scopeDecision(server, null).reason()).contains("trust: forwarded");
    }

    @Test
    void forServerShouldMakeAutoPublicWhenTrustIsNone() {
        McpServerSpec server = new McpServerSpec();
        server.setCacheScope("auto");
        server.setTrust(new McpTrustSpec("none"));

        assertThat(CacheHints.forServer(server).cacheScope()).isEqualTo("public");
    }

    @Test
    void forResourceShouldResolveAResourceAutoAgainstTheAdapter() {
        McpServerSpec server = new McpServerSpec();
        server.setCacheScope("private");
        McpServerResourceSpec resource = new McpServerResourceSpec();
        resource.setCacheScope("auto");

        assertThat(CacheHints.forResource(server, resource).cacheScope()).isEqualTo("public");
    }

    @Test
    void forResourceShouldInheritAdapterAutoWhenResourceSetsNoScope() {
        McpServerSpec server = new McpServerSpec();
        server.setCacheScope("auto");
        server.setAuthentication(new BearerAuthenticationSpec("{{TOKEN}}"));

        assertThat(CacheHints.forResource(server, new McpServerResourceSpec()).cacheScope())
                .isEqualTo("private");
    }

    @Test
    void forResourceShouldRejectANegativeResourceTtl() {
        McpServerResourceSpec resource = new McpServerResourceSpec();
        resource.setTtlMs(-5);

        assertThatThrownBy(() -> CacheHints.forResource(new McpServerSpec(), resource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlMs");
    }
}
