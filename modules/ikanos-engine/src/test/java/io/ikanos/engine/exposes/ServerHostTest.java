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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link ServerHost}: how adapters are grouped into listeners, and the mount
 * rules checked when a capability is built (issue #893). Nothing is started.
 */
class ServerHostTest {

    @Test
    void buildShouldRejectTwoAdaptersAtTheSamePathOfAPort() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(rest("a", 8080, "") + rest("b", 8080, "")));

        assertTrue(error.getMessage().contains("rest adapter 'a' and rest adapter 'b'"),
                error.getMessage());
        assertTrue(error.getMessage().contains("path '/'"), error.getMessage());
    }

    @Test
    void buildShouldRejectAPathNestedInAnotherOne() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(rest("a", 8080, "path: \"/api\"")
                        + rest("b", 8080, "path: \"/api/v2\"")));

        assertTrue(error.getMessage().contains("overlap"), error.getMessage());
    }

    @Test
    void buildShouldAcceptPathsThatOnlyShareCharacters() throws Exception {
        Capability capability = capability(rest("a", 8080, "path: \"/mcp\"")
                + rest("b", 8080, "path: \"/mcp-admin\""));

        assertEquals(1, capability.getServerHost().getSharedListeners().size());
    }

    @Test
    void buildShouldAcceptTheSamePathOnDifferentHosts() throws Exception {
        Capability capability = capability(rest("a", 8080, "hosts: [\"a.example.test\"]")
                + rest("b", 8080, "hosts: [\"b.example.test\"]"));

        assertEquals(1, capability.getServerHost().getSharedListeners().size());
    }

    @Test
    void buildShouldRejectTheSamePathWhenHostListsOverlap() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(rest("a", 8080, "hosts: [\"a.example.test\"]")
                        + rest("b", 8080, "hosts: [\"b.example.test\", \"a.example.test\"]")));

        assertTrue(error.getMessage().contains("host 'a.example.test'"), error.getMessage());
    }

    @Test
    void buildShouldRejectAControlAdapterSharingABusinessPort() {
        String control = """
                    - type: "control"
                      address: "localhost"
                      port: 8080
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(rest("a", 8080, "path: \"/api\"") + control));

        assertTrue(error.getMessage().contains("control adapter"), error.getMessage());
    }

    @Test
    void buildShouldKeepDedicatedServersWhenEveryAdapterHasItsOwnPort() throws Exception {
        Capability capability = capability(rest("a", 8080, "") + rest("b", 8081, ""));

        assertTrue(capability.getServerHost().getSharedListeners().isEmpty());
        for (ServerAdapter adapter : capability.getServerAdapters()) {
            assertFalse(adapter.isSharedServer());
        }
    }

    @Test
    void buildShouldNeverShareEphemeralPorts() throws Exception {
        Capability capability = capability(rest("a", 0, "") + rest("b", 0, ""));

        assertTrue(capability.getServerHost().getSharedListeners().isEmpty());
    }

    @Test
    void buildShouldShareTheListenerOfASingleAdapterWithAPath() throws Exception {
        Capability capability = capability(rest("a", 8080, "path: \"/api\""));

        assertEquals(1, capability.getServerHost().getSharedListeners().size());
        assertTrue(capability.getServerAdapters().get(0).isSharedServer());
    }

    @Test
    void buildShouldRejectAMalformedPath() throws Exception {
        IkanosSpec spec = spec(rest("a", 8080, ""));
        spec.getCapability().getExposes().get(0).setPath("/api/");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new Capability(spec));

        assertTrue(error.getMessage().contains("Invalid 'path' '/api/'"), error.getMessage());
    }

    @Test
    void buildShouldRejectAHostWithAPort() throws Exception {
        IkanosSpec spec = spec(rest("a", 8080, ""));
        spec.getCapability().getExposes().get(0).setHosts(List.of("api.example.test:8080"));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new Capability(spec));

        assertTrue(error.getMessage().contains("Invalid 'hosts' entry"), error.getMessage());
    }

    @Test
    void buildShouldRejectAHostBindingThatResolvesToNothing() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> capability(rest("a", 8080, "hosts: [\"{{UNKNOWN_DOMAIN}}\"]")));

        assertTrue(error.getMessage().contains("resolved to an empty or invalid host name"),
                error.getMessage());
    }

    @Test
    void groupByHostShouldOrderExactNamesBeforeWildcardsBeforeAnyHost() {
        ServerHost.Mount any = new ServerHost.Mount(null, "", List.of());
        ServerHost.Mount wildcard = new ServerHost.Mount(null, "/w", List.of("*.example.test"));
        ServerHost.Mount exact = new ServerHost.Mount(null, "/e", List.of("a.example.test"));

        List<String> order = List.copyOf(
                ServerHost.groupByHost(List.of(any, wildcard, exact)).keySet());

        assertEquals(List.of("a.example.test", "*.example.test", ServerHost.ANY_HOST), order);
    }

    @Test
    void toHostRegexShouldMatchOneLabelForAWildcard() {
        Pattern pattern = Pattern.compile(ServerHost.toHostRegex("*.example.test"));

        assertTrue(pattern.matcher("t1.example.test").matches());
        assertFalse(pattern.matcher("a.b.example.test").matches());
        assertFalse(pattern.matcher("example.test").matches());
    }

    @Test
    void toHostRegexShouldTreatDotsLiterallyForAnExactName() {
        Pattern pattern = Pattern.compile(ServerHost.toHostRegex("a.example.test"));

        assertTrue(pattern.matcher("a.example.test").matches());
        assertFalse(pattern.matcher("aXexample.test").matches());
    }

    @Test
    void isNestedShouldCompareWholeSegments() {
        assertTrue(ServerHost.isNested("/api", "/api/v2"));
        assertFalse(ServerHost.isNested("/api", "/apix"));
        assertFalse(ServerHost.isNested("", "/api"));
    }

    @Test
    void isSegmentBoundaryShouldAcceptEndSlashQueryAndMatrix() {
        assertTrue(SegmentRoute.isSegmentBoundary("/mcp", 4));
        assertTrue(SegmentRoute.isSegmentBoundary("/mcp/tools", 4));
        assertTrue(SegmentRoute.isSegmentBoundary("/mcp?x=1", 4));
        assertTrue(SegmentRoute.isSegmentBoundary("/mcp;v=1", 4));
        assertFalse(SegmentRoute.isSegmentBoundary("/mcpx", 4));
        assertFalse(SegmentRoute.isSegmentBoundary("/mc", 4));
    }

    // ── fixtures ──

    private static String rest(String namespace, int port, String mountLine) {
        return """
                    - type: "rest"
                      address: "localhost"
                      port: %d
                      namespace: "%s"
                      %s
                      resources:
                        ping:
                          path: "/ping"
                          operations:
                            ping:
                              method: "GET"
                              outputParameters:
                                status:
                                  type: "string"
                                  value: "ok"
                """.formatted(port, namespace, mountLine);
    }

    private static IkanosSpec spec(String exposes) throws Exception {
        String yaml = """
                ikanos: "%s"
                info:
                  display: "Server Host Test"
                capability:
                  exposes:
                %s""".formatted(VersionHelper.getSchemaVersion(), exposes);
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }

    private static Capability capability(String exposes) throws Exception {
        return new Capability(spec(exposes));
    }
}
