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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.OAuth2AuthenticationSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * An MCP adapter's {@code authentication.resource} must be its endpoint or a parent of it, or
 * clients that follow MCP authorization bind their tokens to another audience (#1153).
 */
class McpResourceIdentifierTest {

    @Test
    void warningShouldBeNullWhenTheResourceIsTheEndpoint() {
        assertNull(McpResourceIdentifier.warning(adapter("/mcp", "https://mcp.example.org/mcp")));
    }

    @Test
    void warningShouldBeNullWhenTheResourceIsAParentOfTheEndpoint() {
        assertNull(McpResourceIdentifier.warning(adapter("/tools/mcp",
                "https://mcp.example.org/tools")));
        assertNull(McpResourceIdentifier.warning(adapter("/mcp", "https://mcp.example.org")));
        assertNull(McpResourceIdentifier.warning(adapter("/mcp", "https://mcp.example.org/")));
    }

    @Test
    void warningShouldNameTheResourceAndThePathWhenTheResourceIsElsewhere() {
        String warning = McpResourceIdentifier.warning(adapter("/mcp",
                "https://mcp.example.org/api"));

        assertNotNull(warning);
        assertTrue(warning.contains("'https://mcp.example.org/api'"), warning);
        assertTrue(warning.contains("'/mcp'"), warning);
        assertTrue(warning.contains("Invalid audience"), warning);
    }

    @Test
    void warningShouldNotTakeAPathPrefixForAParentSegment() {
        assertNotNull(McpResourceIdentifier.warning(adapter("/mcpx",
                "https://mcp.example.org/mcp")));
    }

    @Test
    void warningShouldBeNullForAnAdapterWithoutPath() {
        // It answers on any path: there is no endpoint path to compare with
        assertNull(McpResourceIdentifier.warning(adapter(null, "https://mcp.example.org/api")));
    }

    @Test
    void warningShouldBeNullWhenAnExplicitAudienceIsChecked() {
        McpServerSpec adapter = adapter("/mcp", "https://mcp.example.org/api");
        ((OAuth2AuthenticationSpec) adapter.getAuthentication())
                .setAudience("https://mcp.example.org/api");

        assertNull(McpResourceIdentifier.warning(adapter));
    }

    @Test
    void isSameOrParentShouldCompareWholeSegments() {
        assertTrue(McpResourceIdentifier.isSameOrParent("/mcp/", "/mcp"));
        assertTrue(McpResourceIdentifier.isSameOrParent("", "/mcp"));
        assertTrue(McpResourceIdentifier.isSameOrParent("/", "/mcp"));
        assertTrue(McpResourceIdentifier.isSameOrParent("/a", "/a/b/c"));
        assertFalse(McpResourceIdentifier.isSameOrParent("/a/b", "/a"));
        assertFalse(McpResourceIdentifier.isSameOrParent("/ab", "/abc"));
    }

    @Test
    void findWarningsShouldReportEachMisboundAdapterOfADocument() throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: Resource identifiers
                  description: One adapter bound elsewhere, one fine
                capability:
                  exposes:
                  - type: mcp
                    address: localhost
                    port: 9201
                    path: /mcp
                    namespace: misbound
                    authentication:
                      type: oauth2
                      authorizationServerUri: https://idp.example.org
                      resource: https://mcp.example.org/api
                    tools:
                      ping:
                        description: Ping.
                        outputParameters:
                        - type: string
                          value: ok
                  - type: mcp
                    address: localhost
                    port: 9202
                    path: /mcp
                    namespace: bound
                    authentication:
                      type: oauth2
                      authorizationServerUri: https://idp.example.org
                      resource: https://mcp.example.org/mcp
                    tools:
                      ping:
                        description: Ping.
                        outputParameters:
                        - type: string
                          value: ok
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);

        List<String> warnings = McpResourceIdentifier.findWarnings(spec);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("'misbound'"), warnings.get(0));
    }

    private static McpServerSpec adapter(String path, String resource) {
        OAuth2AuthenticationSpec oauth2 = new OAuth2AuthenticationSpec();
        oauth2.setAuthorizationServerUri("https://idp.example.org");
        oauth2.setResource(resource);
        McpServerSpec adapter = new McpServerSpec();
        adapter.setNamespace("records-mcp");
        adapter.setPath(path);
        adapter.setAuthentication(oauth2);
        return adapter;
    }
}
