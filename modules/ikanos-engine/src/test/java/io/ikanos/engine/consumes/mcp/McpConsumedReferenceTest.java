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
package io.ikanos.engine.consumes.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * #827: a {@code call:} to a tool a consumed MCP adapter does not declare fails when the
 * capability loads, like one to an undeclared HTTP operation, whatever dots the tool name has.
 */
class McpConsumedReferenceTest {

    private static final ObjectMapper YAML = IkanosMappers.yaml();

    /** The capability of #827: a dotted tool name and a plain one, called from flow steps. */
    private static final String CAPABILITY = """
            ikanos: "%s"
            info:
              display: Demo
              description: demo
            capability:
              consumes:
                - type: mcp
                  namespace: fs
                  description: File server
                  endpoint: https://files.example.com/mcp
                  discovery: "off"
                  tools:
                    files.read:
                      description: Read a file
                    search:
                      description: Search files
                - type: http
                  namespace: api
                  baseUri: https://api.example.com
                  resources:
                    items:
                      path: /items
                      operations:
                        list-items:
                          method: GET
              aggregates:
                - display: demo
                  namespace: demo
                  flows:
                    read:
                      description: Dotted tool name
                      steps:
                        - name: read
                          type: call
                          what: fs.files.read
                    find:
                      description: Plain tool name
                      steps:
                        - name: find
                          type: call
                          what: fs.search
                    direct:
                      description: Flow-level call
                      call: fs.files.read
              exposes:
                - type: mcp
                  namespace: demo-mcp
                  address: localhost
                  port: 0
                  description: Demo
                  tools:
                    read:
                      ref: demo.read
                    find:
                      ref: demo.find
                    raw:
                      description: Calls the consumed tool itself
                      call: fs.search
                  resources:
                    file:
                      display: File
                      uri: demo://file
                      description: Reads through a step
                      steps:
                        - name: read
                          type: call
                          what: fs.files.read
            """;

    private static String capability() {
        return CAPABILITY.formatted(VersionHelper.getSchemaVersion());
    }

    private static Capability load(String yaml) throws Exception {
        return new Capability(YAML.readValue(yaml, IkanosSpec.class));
    }

    private static String replaceOnce(String source, String target, String replacement) {
        int first = source.indexOf(target);
        assertTrue(first >= 0 && source.indexOf(target, first + 1) < 0,
                "fixture anchor must occur exactly once: " + target);
        return source.replace(target, replacement);
    }

    private static String loadError(String yaml) {
        return assertThrows(IllegalArgumentException.class, () -> load(yaml)).getMessage();
    }

    @Test
    void declaresOperationShouldBeTrueOnlyForADeclaredTool() throws Exception {
        McpClientSpec spec = (McpClientSpec) YAML.readValue("""
                type: mcp
                namespace: fs
                endpoint: https://files.example.com/mcp
                discovery: off
                tools:
                  files.read: { description: Read a file }
                  search: { description: Search files }
                """, ClientSpec.class);
        McpClientAdapter adapter = new McpClientAdapter(null, spec);

        assertTrue(adapter.declaresOperation("files.read"));
        assertTrue(adapter.declaresOperation("search"));
        assertFalse(adapter.declaresOperation("serch"));
        assertFalse(adapter.declaresOperation("files.reed"));
        assertFalse(adapter.declaresOperation("files"));
        assertFalse(adapter.declaresOperation("Search"), "tool names are case-sensitive");
    }

    @Test
    void capabilityCallingOnlyDeclaredToolsShouldLoad() {
        assertDoesNotThrow(() -> load(capability()));
    }

    @Test
    void stepCallingAnUndeclaredToolShouldFailAtLoad() {
        String yaml = replaceOnce(capability(), "what: fs.search\n        direct:",
                "what: fs.serch\n        direct:");

        assertEquals("Consumed namespace 'fs' has no operation 'serch' (called from aggregate "
                + "'demo' flow 'find' step 'find')", loadError(yaml));
    }

    @Test
    void stepCallingAnUndeclaredDottedToolShouldFailAtLoad() {
        String yaml = replaceOnce(capability(), "Dotted tool name\n          steps:\n"
                + "            - name: read\n              type: call\n              what: fs.files.read",
                "Dotted tool name\n          steps:\n"
                + "            - name: read\n              type: call\n              what: fs.files.reed");

        assertEquals("Consumed namespace 'fs' has no operation 'files.reed' (called from "
                + "aggregate 'demo' flow 'read' step 'read')", loadError(yaml));
    }

    @Test
    void flowCallToAnUndeclaredDottedToolShouldFailAtLoad() {
        String yaml = replaceOnce(capability(), "Flow-level call\n          call: fs.files.read",
                "Flow-level call\n          call: fs.file.read");

        assertEquals("Consumed namespace 'fs' has no operation 'file.read' (called from "
                + "aggregate 'demo' flow 'direct')", loadError(yaml));
    }

    @Test
    void exposedToolCallingAnUndeclaredToolShouldFailAtLoad() {
        String yaml = replaceOnce(capability(), "itself\n          call: fs.search",
                "itself\n          call: fs.files");

        assertEquals("Consumed namespace 'fs' has no operation 'files' (called from MCP tool "
                + "'raw' in 'demo-mcp')", loadError(yaml));
    }

    @Test
    void exposedResourceStepCallingAnUndeclaredDottedToolShouldFailAtLoad() {
        String yaml = replaceOnce(capability(), "a step\n          steps:\n            - name: read\n"
                + "              type: call\n              what: fs.files.read",
                "a step\n          steps:\n            - name: read\n"
                + "              type: call\n              what: fs.files.read.x");

        assertEquals("Consumed namespace 'fs' has no operation 'files.read.x' (called from MCP "
                + "resource 'file' in 'demo-mcp' step 'read')", loadError(yaml));
    }

    /**
     * The check now splits on the first dot like the executor, so a dotted name after an HTTP
     * namespace is checked too: it failed on the first request before, it fails at load now.
     */
    @Test
    void dottedCallToAnHttpAdapterShouldFailAtLoad() {
        String yaml = replaceOnce(capability(), "what: fs.search\n        direct:",
                "what: api.list-items.extra\n        direct:");

        assertEquals("Consumed namespace 'api' has no operation 'list-items.extra' (called from "
                + "aggregate 'demo' flow 'find' step 'find')", loadError(yaml));
    }
}
