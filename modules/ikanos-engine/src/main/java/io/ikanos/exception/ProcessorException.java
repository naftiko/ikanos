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
package io.ikanos.exception;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.engine.exposes.mcp.model.JsonRpcError;

/**
 * A custom exception for errors raised by dispatch processors.
 */
public class ProcessorException extends Exception {

    private final JsonRpcError rpcError;
    private final ObjectNode result;

    public ProcessorException(String message, JsonRpcError rpcError, ObjectNode result) {
        super(message);
        this.rpcError = rpcError;
        this.result = result;
    }

    public JsonRpcError getRpcError() {
        return rpcError;
    }

    public ObjectNode getResult() {
        return result;
    }
}
