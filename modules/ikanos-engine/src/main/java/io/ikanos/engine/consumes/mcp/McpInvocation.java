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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.observability.OtelNullSafety;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;

/**
 * A prepared MCP {@code tools/call}: arguments are built, nothing is sent until {@link #invoke()}.
 *
 * <p>The client span, its status, and failure recording come from {@link ConsumedInvocation}; this
 * class adds the MCP tool name as a span attribute.</p>
 */
final class McpInvocation extends ConsumedInvocation {

    static final AttributeKey<String> ATTR_MCP_TOOL = AttributeKey.stringKey("mcp.tool.name");

    private final McpClientAdapter adapter;
    private final String toolName;
    private final ObjectNode arguments;
    private final ConsumedOperationView operation;
    private final java.util.Map<String, Object> templates;

    McpInvocation(McpClientAdapter adapter, String toolName, ObjectNode arguments,
            ConsumedOperationView operation, java.util.Map<String, Object> templates) {
        this.adapter = adapter;
        this.toolName = toolName;
        this.arguments = arguments;
        this.operation = operation;
        this.templates = templates;
    }

    /** @return the {@code arguments} object sent upstream (for tests) */
    ObjectNode arguments() {
        return arguments;
    }

    @Override
    public String namespace() {
        return adapter.getNamespace();
    }

    @Override
    public ConsumedOperationView operation() {
        return operation;
    }

    @Override
    protected ClientSpanDescriptor spanDescriptor() {
        return new ClientSpanDescriptor("tools/call", adapter.getEndpoint());
    }

    @Override
    protected ConsumedResult doInvoke() {
        Span.current().setAttribute(OtelNullSafety.nonNullStringKey(ATTR_MCP_TOOL), toolName);
        return adapter.callTool(toolName, arguments, operation, templates);
    }
}
