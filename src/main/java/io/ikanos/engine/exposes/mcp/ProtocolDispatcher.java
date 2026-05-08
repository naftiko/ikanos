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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.restlet.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import io.ikanos.spec.exposes.mcp.McpPromptArgumentSpec;
import io.ikanos.spec.exposes.mcp.McpServerPromptSpec;
import io.ikanos.spec.exposes.mcp.McpServerResourceSpec;

/**
 * Transport-agnostic MCP JSON-RPC protocol dispatcher.
 * 
 * Handles MCP protocol methods (initialize, tools/list, tools/call, resources/list,
 * resources/read, resources/templates/list, prompts/list, prompts/get, ping)
 * and produces JSON-RPC response envelopes. Used by both the Streamable HTTP
 * handler and the stdio handler.
 */
public class ProtocolDispatcher {

    static final String JSONRPC_VERSION = "2.0";
    static final String MCP_PROTOCOL_VERSION = "2025-11-25";

    private final McpServerAdapter adapter;
    private final ObjectMapper mapper;

    public ProtocolDispatcher(McpServerAdapter adapter) {
        this.adapter = adapter;
        this.mapper = new ObjectMapper();
    }

    /**
     * Dispatch a JSON-RPC request and return the response envelope.
     * 
     * @param request the parsed JSON-RPC request
     * @return the JSON-RPC response envelope, or {@code null} for notifications
     */
    public ObjectNode dispatch(JsonNode request) {
        if (request == null) {
            return buildJsonRpcError(null, -32600, "Invalid Request: request body is missing");
        }

        try {
            String jsonrpc = request.path("jsonrpc").asText("");
            JsonNode idNode = request.get("id");
            String rpcMethod = request.path("method").asText("");
            JsonNode params = request.get("params");

            if (!JSONRPC_VERSION.equals(jsonrpc)) {
                return buildJsonRpcError(idNode, -32600,
                        "Invalid Request: jsonrpc must be '2.0'");
            }

            switch (rpcMethod) {
                case "initialize":
                    return handleInitialize(idNode);

                case "notifications/initialized":
                    // Notification — no response
                    return null;

                case "tools/list":
                    return handleToolsList(idNode);

                case "tools/call":
                    return handleToolsCall(idNode, params);

                case "resources/list":
                    return handleResourcesList(idNode);

                case "resources/read":
                    return handleResourcesRead(idNode, params);

                case "resources/templates/list":
                    return handleResourcesTemplatesList(idNode);

                case "prompts/list":
                    return handlePromptsList(idNode);

                case "prompts/get":
                    return handlePromptsGet(idNode, params);

                case "ping":
                    return buildJsonRpcResult(idNode, mapper.createObjectNode());

                default:
                    return buildJsonRpcError(idNode, -32601,
                            "Method not found: " + rpcMethod);
            }
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error processing request", e);
            return buildJsonRpcError(null, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Handle MCP initialize request.
     */
    private ObjectNode handleInitialize(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);

        // Conditionally advertise capabilities based on what is declared in the spec
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.putObject("tools");
        if (!adapter.getMcpServerSpec().getResources().isEmpty()) {
            capabilities.putObject("resources");
        }
        if (!adapter.getMcpServerSpec().getPrompts().isEmpty()) {
            capabilities.putObject("prompts");
        }
        result.set("capabilities", capabilities);

        // Server info
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", adapter.getMcpServerSpec().getNamespace());
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);

        // Instructions from the spec description
        if (adapter.getMcpServerSpec().getDescription() != null) {
            result.put("instructions", adapter.getMcpServerSpec().getDescription());
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle tools/list request.
     */
    private ObjectNode handleToolsList(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");
        Map<String, String> labels = adapter.getToolLabels();

        for (McpSchema.Tool tool : adapter.getTools()) {
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

            if (tool.annotations() != null) {
                ObjectNode annotationsNode = mapper.createObjectNode();
                McpSchema.ToolAnnotations ann = tool.annotations();
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
                if (!annotationsNode.isEmpty()) {
                    toolNode.set("annotations", annotationsNode);
                }
            }

            toolsArray.add(toolNode);
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle tools/call request.
     */
    @SuppressWarnings("unchecked")
    private ObjectNode handleToolsCall(JsonNode id, JsonNode params) {
        if (params == null) {
            return buildJsonRpcError(id, -32602, "Invalid params: missing params");
        }

        String toolName = params.path("name").asText("");
        JsonNode argumentsNode = params.get("arguments");

        try {
            Map<String, Object> arguments = argumentsNode != null
                    ? mapper.treeToValue(argumentsNode, Map.class)
                    : new ConcurrentHashMap<>();
            McpSchema.CallToolResult toolResult =
                    adapter.getToolHandler().handleToolCall(toolName, arguments);
            ObjectNode result = mapper.valueToTree(toolResult);
            return buildJsonRpcResult(id, result);
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling tools call", e);
            return buildJsonRpcError(id, -32602, "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling tools call", e);
            // Tool execution error — return as a tool result with isError=true
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textContent = content.addObject();
            textContent.put("type", "text");
            textContent.put("text", "Error: " + e.getMessage());
            result.put("isError", true);
            return buildJsonRpcResult(id, result);
        }
    }

    /**
     * Handle resources/list request.
     */
    private ObjectNode handleResourcesList(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode resourcesArray = result.putArray("resources");

        for (Map<String, String> entry : adapter.getResourceHandler().listAll()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("uri", entry.get("uri"));
            node.put("name", entry.get("name"));
            String title = entry.get("label");
            if (title != null) {
                node.put("title", title);
            }
            String description = entry.get("description");
            if (description != null) {
                node.put("description", description);
            }
            String mimeType = entry.get("mimeType");
            if (mimeType != null) {
                node.put("mimeType", mimeType);
            }
            resourcesArray.add(node);
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle resources/read request.
     */
    private ObjectNode handleResourcesRead(JsonNode id, JsonNode params) {
        if (params == null) {
            return buildJsonRpcError(id, -32602, "Invalid params: missing params");
        }
        String uri = params.path("uri").asText("");
        if (uri.isEmpty()) {
            return buildJsonRpcError(id, -32602, "Invalid params: uri is required");
        }

        try {
            List<ResourceHandler.ResourceContent> contents =
                    adapter.getResourceHandler().read(uri);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode contentsArray = result.putArray("contents");
            for (ResourceHandler.ResourceContent c : contents) {
                ObjectNode contentNode = mapper.createObjectNode();
                contentNode.put("uri", c.uri);
                if (c.mimeType != null) {
                    contentNode.put("mimeType", c.mimeType);
                }
                if (c.blob != null) {
                    contentNode.put("blob", c.blob);
                } else {
                    contentNode.put("text", c.text != null ? c.text : "");
                }
                contentsArray.add(contentNode);
            }
            return buildJsonRpcResult(id, result);
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling resources/read", e);
            return buildJsonRpcError(id, -32602, "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling resources/read", e);
            return buildJsonRpcError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Handle resources/templates/list request.
     */
    private ObjectNode handleResourcesTemplatesList(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode templatesArray = result.putArray("resourceTemplates");

        for (McpServerResourceSpec spec : adapter.getResourceHandler().listTemplates()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("uriTemplate", spec.getUri());
            node.put("name", spec.getName());
            if (spec.getLabel() != null) {
                node.put("title", spec.getLabel());
            }
            if (spec.getDescription() != null) {
                node.put("description", spec.getDescription());
            }
            if (spec.getMimeType() != null) {
                node.put("mimeType", spec.getMimeType());
            }
            templatesArray.add(node);
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle prompts/list request.
     */
    private ObjectNode handlePromptsList(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode promptsArray = result.putArray("prompts");

        for (McpServerPromptSpec spec : adapter.getPromptHandler().listAll()) {
            ObjectNode promptNode = mapper.createObjectNode();
            promptNode.put("name", spec.getName());
            if (spec.getLabel() != null) {
                promptNode.put("title", spec.getLabel());
            }
            if (spec.getDescription() != null) {
                promptNode.put("description", spec.getDescription());
            }
            if (!spec.getArguments().isEmpty()) {
                ArrayNode argsArray = promptNode.putArray("arguments");
                for (McpPromptArgumentSpec arg : spec.getArguments()) {
                    ObjectNode argNode = mapper.createObjectNode();
                    argNode.put("name", arg.getName());
                    if (arg.getLabel() != null) {
                        argNode.put("title", arg.getLabel());
                    }
                    if (arg.getDescription() != null) {
                        argNode.put("description", arg.getDescription());
                    }
                    argNode.put("required", arg.isRequired());
                    argsArray.add(argNode);
                }
            }
            promptsArray.add(promptNode);
        }

        return buildJsonRpcResult(id, result);
    }

    /**
     * Handle prompts/get request — render a prompt with the provided arguments.
     */
    @SuppressWarnings("unchecked")
    private ObjectNode handlePromptsGet(JsonNode id, JsonNode params) {
        if (params == null) {
            return buildJsonRpcError(id, -32602, "Invalid params: missing params");
        }
        String name = params.path("name").asText("");
        if (name.isEmpty()) {
            return buildJsonRpcError(id, -32602, "Invalid params: name is required");
        }

        try {
            JsonNode argumentsNode = params.get("arguments");
            Map<String, Object> arguments = argumentsNode != null
                    ? mapper.treeToValue(argumentsNode, Map.class)
                    : new java.util.HashMap<>();

            List<PromptHandler.RenderedMessage> messages =
                    adapter.getPromptHandler().render(name, arguments);

            ObjectNode result = mapper.createObjectNode();
            ArrayNode messagesArray = result.putArray("messages");
            for (PromptHandler.RenderedMessage msg : messages) {
                ObjectNode msgNode = mapper.createObjectNode();
                msgNode.put("role", msg.role);
                ObjectNode contentNode = msgNode.putObject("content");
                contentNode.put("type", "text");
                contentNode.put("text", msg.text);
                messagesArray.add(msgNode);
            }
            return buildJsonRpcResult(id, result);
        } catch (IllegalArgumentException e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling prompts/get", e);
            return buildJsonRpcError(id, -32602, "Invalid params: " + e.getMessage());
        } catch (Exception e) {
            Context.getCurrentLogger().log(Level.SEVERE, "Error handling prompts/get", e);
            return buildJsonRpcError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Build a JSON-RPC success response envelope.
     */
    ObjectNode buildJsonRpcResult(JsonNode id, JsonNode result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        Context.getCurrentLogger().log(Level.INFO, "Building JSON-RPC result for id: " + id);

        if (id != null) {
            envelope.set("id", id);
        }
        
        envelope.set("result", result);
        return envelope;
    }

    /**
     * Build a JSON-RPC error response envelope.
     */
    ObjectNode buildJsonRpcError(JsonNode id, int code, String message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        Context.getCurrentLogger().log(Level.INFO, "Building JSON-RPC error for id: " + id);

        if (id != null) {
            envelope.set("id", id);
        } else {
            envelope.putNull("id");
        }

        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return envelope;
    }

    ObjectMapper getMapper() {
        return mapper;
    }

}
