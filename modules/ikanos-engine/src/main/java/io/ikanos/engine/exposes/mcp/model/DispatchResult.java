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
package io.ikanos.engine.exposes.mcp.model;

import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.McpSubscription;

/**
 * A record representing a dispatch result.
 *
 * @param rpcError     the JsonRpc error if relevant (an error occurred), null otherwise.
 * @param isOnError    true if an error occurred, false otherwise.
 * @param responseBody the response body.
 * @param subscription the stream a {@code subscriptions/listen} request opened, which the
 *                     transport runs in place of a response body; null otherwise.
 */
public record DispatchResult(ObjectNode responseBody, boolean isOnError, JsonRpcError rpcError,
        McpSubscription subscription) {

    public DispatchResult(ObjectNode responseBody, JsonRpcError rpcError) {
        this(responseBody, true, rpcError, null);
    }

    public DispatchResult(ObjectNode responseBody) {
        this(responseBody, false, null, null);
    }

    public DispatchResult(McpSubscription subscription) {
        this(null, false, null, subscription);
    }
}
