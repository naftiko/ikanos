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
package io.ikanos.engine.observability;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.control.ControlServerAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Embeds the engine and calls one MCP tool in-process, the way a host application does.
 *
 * <p>Loaded by {@link TelemetryWithoutSdkTest} through a class loader that hides the
 * OpenTelemetry SDK. It must therefore not reference any {@code io.opentelemetry.sdk} type,
 * directly or through a test fixture. It implements {@link Callable}, which both class loaders
 * share, so the test can call it without reflection.</p>
 */
public class EmbeddedToolCallProbe implements Callable<String> {

    private static final String CONTROL_PORT = """
                - type: control
                  address: localhost
                  port: 0
                  observability:
                    traces:
                      local:
                        buffer-size: 10
            """;

    /** When true, the capability also exposes a control port with local trace capture on. */
    private final boolean withControlPort;

    /** Probe with a single MCP expose. */
    public EmbeddedToolCallProbe() {
        this(false);
    }

    /** Probe with an MCP expose and, optionally, a control port. */
    public EmbeddedToolCallProbe(boolean withControlPort) {
        this.withControlPort = withControlPort;
    }

    /** Builds a capability with telemetry off, calls its tool and returns the text result. */
    @Override
    public String call() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: mcp
                      address: localhost
                      port: 0
                      namespace: probe
                      tools:
                        hello:
                          description: Say hello
                          outputParameters:
                            - type: string
                              value: hello
                %s""".formatted(VersionHelper.getSchemaVersion(), withControlPort ? CONTROL_PORT : "");
        Capability capability = Capability.builder()
                .spec(IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class))
                .telemetry(false)
                .build();
        McpServerAdapter adapter = null;
        boolean hasControlPort = false;
        for (ServerAdapter server : capability.getServerAdapters()) {
            if (server instanceof McpServerAdapter mcp) {
                adapter = mcp;
            }
            hasControlPort |= server instanceof ControlServerAdapter;
        }
        if (adapter == null) {
            throw new IllegalStateException("probe capability has no MCP expose");
        }
        if (withControlPort && !hasControlPort) {
            throw new IllegalStateException("probe capability has no control expose");
        }
        McpSchema.CallToolResult result = adapter.getToolHandler().handleToolCall("hello", Map.of());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return Boolean.TRUE.equals(result.isError()) ? "error: " + text : text;
    }
}
