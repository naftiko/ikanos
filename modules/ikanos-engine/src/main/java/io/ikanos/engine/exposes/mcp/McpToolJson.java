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

import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes one MCP tool definition as it appears in a {@code tools/list} result.
 *
 * <p>This is the only serialiser for tool definitions, so every place that hands a definition to
 * a client (today {@code tools/list}) produces the same bytes. Field order is part of the
 * contract: clients cache the prompt prefix that holds tool definitions, and a reordered field
 * breaks that cache.</p>
 */
public final class McpToolJson {

    private McpToolJson() {
        // Utility class — no instances.
    }

    /**
     * Writes a tool definition: {@code name}, {@code title}, {@code description},
     * {@code inputSchema}, {@code outputSchema}, {@code annotations} and {@code _meta}, each only
     * when present.
     *
     * @param tool the tool to write
     * @param labels display titles by tool name; a tool without an entry gets no {@code title}
     * @param mapper the mapper used to create nodes and convert schemas
     * @return the tool definition
     */
    public static ObjectNode write(McpSchema.Tool tool, Map<String, String> labels,
            ObjectMapper mapper) {
        ObjectNode toolNode = mapper.createObjectNode();
        toolNode.put("name", tool.name());

        String title = labels.get(tool.name());
        if (title != null) {
            toolNode.put("title", title);
        }

        if (tool.description() != null) {
            toolNode.put("description", tool.description());
        }

        if (tool.inputSchema() != null) {
            toolNode.set("inputSchema", mapper.valueToTree(tool.inputSchema()));
        }

        if (tool.outputSchema() != null) {
            toolNode.set("outputSchema", mapper.valueToTree(tool.outputSchema()));
        }

        ObjectNode annotationsNode = writeAnnotations(tool.annotations(), mapper);
        if (annotationsNode != null) {
            toolNode.set("annotations", annotationsNode);
        }

        if (tool.meta() != null && !tool.meta().isEmpty()) {
            toolNode.set("_meta", mapper.valueToTree(tool.meta()));
        }

        return toolNode;
    }

    /**
     * Writes the annotations that are set, or returns {@code null} when there are none.
     */
    static ObjectNode writeAnnotations(McpSchema.ToolAnnotations ann, ObjectMapper mapper) {
        if (ann == null) {
            return null;
        }
        ObjectNode annotationsNode = mapper.createObjectNode();
        if (ann.title() != null) {
            annotationsNode.put("title", ann.title());
        }
        if (ann.readOnlyHint() != null) {
            annotationsNode.put("readOnlyHint", ann.readOnlyHint());
        }
        if (ann.destructiveHint() != null) {
            annotationsNode.put("destructiveHint", ann.destructiveHint());
        }
        if (ann.idempotentHint() != null) {
            annotationsNode.put("idempotentHint", ann.idempotentHint());
        }
        if (ann.openWorldHint() != null) {
            annotationsNode.put("openWorldHint", ann.openWorldHint());
        }
        return annotationsNode.isEmpty() ? null : annotationsNode;
    }
}
