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

import static org.restlet.data.Protocol.HTTP;
import static org.restlet.data.Protocol.HTTPS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.restlet.Client;
import org.restlet.Context;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ConsumedInvocation;
import io.ikanos.engine.consumes.ConsumedOperationView;
import io.ikanos.engine.consumes.ConsumedResult;
import io.ikanos.engine.consumes.http.ConsumedAuthentication;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientToolSpec;
import io.opentelemetry.api.trace.Span;

/**
 * Consumed adapter for one upstream MCP server ({@code consumes: type: mcp}).
 *
 * <p>Reached from steps through {@link #prepare(String, Map)} like any consumed adapter. Only
 * declared tools are callable; upstream descriptions and annotations are never forwarded.</p>
 *
 * <ul>
 *   <li><b>Request</b> — {@code params.arguments} holds the tool's declared
 *       {@code inputParameters}, taken from the call parameters by argument name.</li>
 *   <li><b>Response</b> — {@code structuredContent} first; else exactly one text block, parsed as
 *       JSON when possible; anything else fails. {@code isError}, a JSON-RPC error, and a
 *       {@code resultType} other than {@code complete} fail the call.</li>
 *   <li><b>Discovery</b> — with {@code discovery: verify}, {@link #start()} lists upstream tools,
 *       fails on a missing tool, an undeclared upstream argument, or an unmet required argument,
 *       and caches each {@code outputSchema} for validation.</li>
 * </ul>
 */
public class McpClientAdapter extends ClientAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Strict reader: a text block is JSON only if the whole text is one JSON value. */
    private static final ObjectReader STRICT_JSON =
            JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final Client client;
    private final McpStreamableHttpTransport transport;
    private final Map<String, JsonSchema> outputSchemas = new ConcurrentHashMap<>();

    public McpClientAdapter(Capability capability, McpClientSpec spec) {
        this(capability, spec, new Client(List.of(HTTP, HTTPS)));
    }

    /** Constructor with an injectable Restlet client, for tests. */
    McpClientAdapter(Capability capability, McpClientSpec spec, Client client) {
        super(capability, spec);
        if (spec.getEndpoint() == null || spec.getEndpoint().isBlank()) {
            throw new IllegalArgumentException(
                    "consumes type mcp '" + spec.getNamespace() + "' requires an endpoint");
        }
        this.client = client;
        this.transport = new McpStreamableHttpTransport(client, spec.getEndpoint(),
                spec.getAuthentication());
    }

    public McpClientSpec getMcpClientSpec() {
        return (McpClientSpec) getSpec();
    }

    public String getEndpoint() {
        return getMcpClientSpec().getEndpoint();
    }

    /** @return the cached upstream output schema for a tool, or {@code null} */
    JsonSchema outputSchema(String toolName) {
        return outputSchemas.get(toolName);
    }

    @Override
    public ConsumedInvocation prepare(String operationName, Map<String, Object> parameters) {
        McpClientToolSpec tool = getMcpClientSpec().getTools().get(operationName);
        if (tool == null) {
            return null;
        }
        ObjectNode arguments = JSON.createObjectNode();
        for (InputParameterSpec param : tool.getInputParameters()) {
            Object value = parameters != null ? parameters.get(param.getName()) : null;
            if (value == null) {
                if (param.isRequired()) {
                    throw new IllegalArgumentException("Missing required argument '"
                            + param.getName() + "' for MCP tool " + getNamespace() + "."
                            + operationName);
                }
                continue;
            }
            arguments.set(param.getName(), JSON.valueToTree(value));
        }
        ConsumedOperationView view = ConsumedOperationView.of(tool, null, null);
        Map<String, Object> templates = ConsumedAuthentication.withBindings(parameters,
                getCapability() != null ? getCapability().getBindings() : null);
        return new McpInvocation(this, operationName, arguments, view, templates);
    }

    /** Execute {@code tools/call} and select the response body. Called inside the client span. */
    ConsumedResult callTool(String toolName, ObjectNode arguments, ConsumedOperationView view,
            Map<String, Object> templates) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        JsonNode result = transport.call("tools/call", toolName, params, templates);

        String resultType = result.path("resultType").asText("complete");
        if (!"complete".equals(resultType)) {
            throw new McpClientException("Upstream MCP tool " + getNamespace() + "." + toolName
                    + " requested '" + resultType + "'; not supported in v1");
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpClientException("Upstream MCP tool " + getNamespace() + "." + toolName
                    + " reported an error: " + firstText(result));
        }

        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) {
            validate(toolName, structured);
        }
        return selectBody(result, view);
    }

    /**
     * Select the response document. Package-private for tests.
     *
     * <ol>
     *   <li>{@code structuredContent} present → it is the document;</li>
     *   <li>exactly one text block that parses as JSON → the parsed value;</li>
     *   <li>exactly one text block, not JSON → the raw string;</li>
     *   <li>anything else → failure naming the content types.</li>
     * </ol>
     */
    static ConsumedResult selectBody(JsonNode result, ConsumedOperationView view) {
        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) {
            return new McpConsumedResult(view, structured, null);
        }
        JsonNode content = result.path("content");
        if (content.isArray() && content.isEmpty()) {
            return new McpConsumedResult(view, null, null);
        }
        if (content.isArray() && content.size() == 1
                && "text".equals(content.get(0).path("type").asText())) {
            String text = content.get(0).path("text").asText("");
            try {
                JsonNode parsed = STRICT_JSON.readTree(text);
                if (parsed != null && !parsed.isMissingNode()) {
                    return new McpConsumedResult(view, parsed, text);
                }
            } catch (IOException notJson) {
                // fall through to the raw string
            }
            return new McpConsumedResult(view, JSON.getNodeFactory().textNode(text), text);
        }
        List<String> types = new ArrayList<>();
        content.forEach(block -> types.add(block.path("type").asText("?")));
        throw new McpClientException("Unsupported MCP tool result content " + types
                + "; v1 accepts structuredContent or a single text block");
    }

    private static String firstText(JsonNode result) {
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText("");
            }
        }
        return "(no text content)";
    }

    /** Validate {@code structuredContent} against the cached upstream output schema. */
    void validate(String toolName, JsonNode structured) {
        JsonSchema schema = outputSchemas.get(toolName);
        McpClientToolSpec tool = getMcpClientSpec().getTools().get(toolName);
        if (schema == null || tool == null
                || McpClientToolSpec.VALIDATE_OFF.equals(tool.validationMode())) {
            return;
        }
        Set<ValidationMessage> errors = schema.validate(structured);
        if (errors.isEmpty()) {
            return;
        }
        String detail = errors.stream().map(ValidationMessage::getMessage).sorted()
                .collect(Collectors.joining("; "));
        if (McpClientToolSpec.VALIDATE_WARN.equals(tool.validationMode())) {
            Span.current().addEvent("mcp.output_schema.violation: " + detail);
            Context.getCurrentLogger().warning("MCP tool " + getNamespace() + "." + toolName
                    + " returned structuredContent violating its outputSchema: " + detail);
            return;
        }
        throw new McpClientException("MCP tool " + getNamespace() + "." + toolName
                + " returned structuredContent violating its outputSchema: " + detail);
    }

    @Override
    public void start() throws Exception {
        client.start();
        if (getMcpClientSpec().isDiscoveryVerify()) {
            verify(listTools());
        }
    }

    @Override
    public void stop() throws Exception {
        client.stop();
    }

    /** List all upstream tools, following {@code nextCursor}. */
    List<JsonNode> listTools() {
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        Map<String, Object> templates = ConsumedAuthentication.withBindings(null,
                getCapability() != null ? getCapability().getBindings() : null);
        do {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = transport.call("tools/list", null, params, templates);
            result.path("tools").forEach(tools::add);
            JsonNode next = result.get("nextCursor");
            cursor = next != null && !next.isNull() && !next.asText().isEmpty()
                    ? next.asText() : null;
        } while (cursor != null);
        return tools;
    }

    /**
     * Check declared tools against the upstream listing and cache output schemas. Package-private
     * for tests.
     *
     * @throws IllegalStateException naming every mismatch
     */
    void verify(List<JsonNode> upstreamTools) {
        Map<String, JsonNode> byName = new ConcurrentHashMap<>();
        upstreamTools.forEach(t -> byName.put(t.path("name").asText(), t));
        List<String> problems = new ArrayList<>();

        for (McpClientToolSpec tool : getMcpClientSpec().getTools().values()) {
            JsonNode upstream = byName.get(tool.getName());
            if (upstream == null) {
                problems.add("tool '" + tool.getName() + "' not found upstream");
                continue;
            }
            JsonNode inputProps = upstream.path("inputSchema").path("properties");
            List<String> declared = tool.getInputParameters().stream()
                    .map(InputParameterSpec::getName).toList();
            for (String name : declared) {
                if (inputProps.isObject() && !inputProps.has(name)) {
                    problems.add("tool '" + tool.getName() + "' declares argument '" + name
                            + "' unknown upstream");
                }
            }
            for (Iterator<JsonNode> it = upstream.path("inputSchema").path("required")
                    .elements(); it.hasNext();) {
                String required = it.next().asText();
                if (!declared.contains(required)) {
                    problems.add("tool '" + tool.getName() + "' does not declare required"
                            + " upstream argument '" + required + "'");
                }
            }
            warnOnHintMismatch(tool, upstream.path("annotations"));
            JsonNode outputSchema = upstream.get("outputSchema");
            if (outputSchema != null && outputSchema.isObject()) {
                outputSchemas.put(tool.getName(), SCHEMAS.getSchema(outputSchema));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("consumes type mcp '" + getNamespace() + "' ("
                    + getEndpoint() + ") failed discovery: " + String.join("; ", problems));
        }
    }

    private void warnOnHintMismatch(McpClientToolSpec tool, JsonNode annotations) {
        if (tool.getHints() == null || annotations == null || annotations.isMissingNode()) {
            return;
        }
        Boolean localReadOnly = tool.getHints().getReadOnly();
        JsonNode upstreamReadOnly = annotations.get("readOnlyHint");
        if (Boolean.TRUE.equals(localReadOnly) && upstreamReadOnly != null
                && !upstreamReadOnly.asBoolean()) {
            Context.getCurrentLogger().warning("MCP tool " + getNamespace() + "."
                    + tool.getName() + " is declared readOnly locally but the upstream reports"
                    + " readOnlyHint=false");
        }
    }
}
