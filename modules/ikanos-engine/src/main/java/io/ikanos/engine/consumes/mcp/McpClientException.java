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

/**
 * A failed exchange with an upstream MCP server: transport, HTTP, JSON-RPC or tool-level failure.
 *
 * <p>Carries no status code: exposers currently report every consumed-call failure the same way,
 * so a status here would suggest a mapping that does not exist.</p>
 */
public class McpClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
