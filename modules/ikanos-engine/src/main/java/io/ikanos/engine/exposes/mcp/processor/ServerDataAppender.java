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
package io.ikanos.engine.exposes.mcp.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.engine.exposes.mcp.ProtocolDispatcher;
import io.ikanos.exception.ProcessorException;
import io.ikanos.spec.InfoSpec;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * A post-processor that appends server data to responses.
 */
public class ServerDataAppender implements DispatchPostProcessor {

    private final JsonNode serverInfo;

    public ServerDataAppender(McpServerAdapter adapter, ObjectMapper mapper) {
        ObjectNode serverInfoData = mapper.createObjectNode();
        serverInfoData.put("name", adapter.getMcpServerSpec().getNamespace());
        InfoSpec infoSpec = adapter.getCapability().getSpec().getInfo();
        if (infoSpec != null && infoSpec.getVersion() != null) {
            serverInfoData.put("version", infoSpec.getVersion());
        } else {
            serverInfoData.put("version", "unspecified");
        }
        ObjectNode severInfoObjectNode = mapper.createObjectNode();
        severInfoObjectNode.set("io.modelcontextprotocol/serverInfo", serverInfoData);
        this.serverInfo = severInfoObjectNode;
    }

    @Override
    public void apply(ObjectNode response) throws ProcessorException {
        response.put("jsonrpc", ProtocolDispatcher.JSONRPC_VERSION);
        ObjectNode result = (ObjectNode) response.get("result");

        if (result == null) {
            throw new ProcessorException("Internal error: expected a result tag", INTERNAL_ERROR,
                    buildJsonRpcError(null, INTERNAL_ERROR.getCode(), "Internal error: expected a result tag"));
        }

        result.set("_meta", serverInfo);
    }
}
