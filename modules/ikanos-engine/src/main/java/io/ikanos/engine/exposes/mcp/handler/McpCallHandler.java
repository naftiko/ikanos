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
package io.ikanos.engine.exposes.mcp.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

import java.util.List;

/**
 * A common base class for all MCP handlers.
 */
public abstract class McpCallHandler {

    protected final ObjectMapper MAPPER = new ObjectMapper();
    protected final McpServerAdapter adapter;
    private final List<McpHeader> requiredHeaders;
    private final List<DispatchPreProcessor> preProcessors;
    private final List<DispatchPostProcessor> postProcessors;

    public McpCallHandler(McpServerAdapter adapter,
                             List<McpHeader> requiredHeaders,
                             List<DispatchPreProcessor> preProcessors,
                             List<DispatchPostProcessor> postProcessors) {
        this.adapter = adapter;
        this.requiredHeaders = requiredHeaders;
        this.preProcessors = preProcessors;
        this.postProcessors = postProcessors;
    }

    /**
     * Handles an MCP call for this handler.
     * @param requestBody the JsonRpc request's body.
     * @return the handler's result.
     */
    public abstract HandlerResult handle(JsonNode requestBody);

    /**
     * Get the list of headers that are required by this handler.
     * @return the list of headers required by this handler.
     */
    public List<McpHeader> getRequiredHeaders() {
        return requiredHeaders;
    }

    /**
     * Get the list of pre-processors to apply to incoming requests before invoking
     * the handler.
     * @return the list of pre-processors to run.
     */
    public List<DispatchPreProcessor> getPreProcessors() {
        return preProcessors;
    }

    /**
     * Get the list of post-processors to apply to outgoing responses after invoking
     * the handler.
     * @return the list of post-processors to run.
     */
    public List<DispatchPostProcessor> getPostProcessors() {
        return postProcessors;
    }

    /**
     * Get the handler's method name.
     * @return the handler's method name.
     */
    public abstract String getMethodName();
}
