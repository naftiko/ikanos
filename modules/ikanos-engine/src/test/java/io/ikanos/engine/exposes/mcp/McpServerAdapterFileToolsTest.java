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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/** Which tools of an MCP adapter take a file, and the load check of the files block. */
class McpServerAdapterFileToolsTest {

    private static Capability capability(String files) throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  exposes:
                  - type: mcp
                    port: 0
                    namespace: documents-mcp
                %s
                    tools:
                      import-report:
                        description: Import a report.
                        inputParameters:
                          report:
                            type: file
                            description: The report.
                        outputParameters:
                          status:
                            type: string
                            value: imported
                      ping:
                        description: Check that the server answers.
                        outputParameters:
                          status:
                            type: string
                            value: pong
                """.formatted(VersionHelper.getSchemaVersion(), files.indent(4).stripTrailing()),
                IkanosSpec.class);
        return new Capability(spec);
    }

    @Test
    void constructorShouldFailWhenAToolTakesAFileWithoutAFilesBlock() {
        Exception refused = assertThrows(Exception.class, () -> capability(""));

        Throwable cause = refused;
        while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("import-report"), cause.getMessage());
        assertTrue(cause.getMessage().contains("files"), cause.getMessage());
    }

    @Test
    void takesFilesShouldBeTrueOnlyForAToolWithAFileInput() throws Exception {
        McpServerAdapter adapter =
                (McpServerAdapter) capability("files: {}").getServerAdapters().getFirst();

        assertTrue(adapter.takesFiles("import-report"));
        assertFalse(adapter.takesFiles("ping"));
    }

    @Test
    void takesFilesShouldBeTrueForARefToolThatRedeclaresTheFlowFileInput() throws Exception {
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  aggregates:
                  - namespace: documents
                    flows:
                      import-report:
                        description: Import a report.
                        inputParameters:
                          report:
                            type: file
                            description: The report.
                        outputParameters:
                          status:
                            type: string
                            value: imported
                  exposes:
                  - type: mcp
                    port: 0
                    namespace: documents-mcp
                    files: {}
                    tools:
                      import-report:
                        description: Import a report.
                        ref: documents.import-report
                        inputParameters:
                          report:
                            type: object
                            description: The report, redeclared.
                """.formatted(VersionHelper.getSchemaVersion()), IkanosSpec.class);
        McpServerAdapter adapter =
                (McpServerAdapter) new Capability(spec).getServerAdapters().getFirst();

        assertTrue(adapter.takesFiles("import-report"),
                "the flow checks its file input whatever the tool declares");
    }

    @Test
    void maxInlineBytesShouldDefaultToOneMebibyte() throws Exception {
        McpServerAdapter adapter =
                (McpServerAdapter) capability("files: {}").getServerAdapters().getFirst();

        assertEquals(1024 * 1024, adapter.getMaxInlineBytes());
    }
}
