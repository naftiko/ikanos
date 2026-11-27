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

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INVALID_PARAMS;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpCallContext;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.McpSubscription;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.HandlerStreamResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.model.RequestClientCapabilities;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

/**
 * The {@code subscriptions/listen} handler (MCP 2026-07-28), for the task status notifications
 * of the tasks extension (SEP-2663), the only notification type this server sends on a
 * subscription: it opens an {@link McpSubscription} to the caller's tasks among
 * {@code notifications.taskIds}, which the transport then runs. Every other filter field is left
 * out of the acknowledgement, as the spec asks for a type the server does not support.
 *
 * <p>Asking for task notifications without declaring the tasks extension in the request's client
 * capabilities gets {@code -32021}. Registered only when the adapter serves tasks.</p>
 */
public class SubscriptionsListenHandler extends McpCallHandler {

    public static final String METHOD_NAME = McpSubscription.LISTEN;

    public SubscriptionsListenHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders,
            List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode idNode = requestBody.get("id");
        JsonNode params = requestBody.path("params");
        JsonNode filter = params.path("notifications");
        if (!filter.isObject()) {
            return invalid(idNode, "notifications must be an object");
        }
        JsonNode requested = filter.path("taskIds");
        List<String> taskIds = new ArrayList<>();
        if (!requested.isMissingNode() && !requested.isNull()) {
            if (!requested.isArray()) {
                return invalid(idNode, "notifications.taskIds must be an array of task IDs");
            }
            for (JsonNode taskId : requested) {
                if (!taskId.isString()) {
                    return invalid(idNode, "notifications.taskIds must be an array of task IDs");
                }
                taskIds.add(taskId.asString());
            }
            if (!RequestClientCapabilities.from(params).supportsTasks()) {
                ObjectNode required = MAPPER.createObjectNode();
                required.putObject("extensions")
                        .putObject(RequestClientCapabilities.TASKS_EXTENSION);
                return missingCapability(idNode, "Missing required client capability", required);
            }
        }
        return new HandlerStreamResult(
                adapter.subscribe(idNode, McpCallContext.principal(), taskIds));
    }

    private HandlerResult invalid(JsonNode idNode, String message) {
        return new HandlerFailureResult(INVALID_PARAMS,
                buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Invalid params: " + message));
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
