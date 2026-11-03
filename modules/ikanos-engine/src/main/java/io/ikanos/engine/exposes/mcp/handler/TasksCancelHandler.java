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
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.model.HandlerFailureResult;
import io.ikanos.engine.exposes.mcp.model.HandlerResult;
import io.ikanos.engine.exposes.mcp.model.HandlerSuccessResult;
import io.ikanos.engine.exposes.mcp.model.McpHeader;
import io.ikanos.engine.exposes.mcp.model.McpResultType;
import io.ikanos.engine.exposes.mcp.processor.DispatchPostProcessor;
import io.ikanos.engine.exposes.mcp.processor.DispatchPreProcessor;

/**
 * The {@code tasks/cancel} handler of the tasks extension (SEP-2663). The request is acknowledged
 * at once with an empty result; the task reaches {@code cancelled} unless its work finished first.
 */
public class TasksCancelHandler extends McpCallHandler {

    public static final String METHOD_NAME = "tasks/cancel";

    public TasksCancelHandler(McpServerAdapter adapter, List<McpHeader> requiredHeaders,
            List<DispatchPreProcessor> preProcessors, List<DispatchPostProcessor> postProcessors) {
        super(adapter, requiredHeaders, preProcessors, postProcessors);
    }

    @Override
    public HandlerResult handle(JsonNode requestBody) {
        JsonNode idNode = requestBody.get("id");
        String taskId = requestBody.path("params").path("taskId").asString(null);
        boolean known = adapter.getTaskService() != null && adapter.getTaskService().cancel(taskId);
        if (!known) {
            return new HandlerFailureResult(INVALID_PARAMS,
                    buildJsonRpcError(idNode, INVALID_PARAMS.getCode(), "Task not found"));
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put(McpResultType.FIELD, McpResultType.COMPLETE.wireValue());
        return new HandlerSuccessResult(result);
    }

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }
}
