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

import io.ikanos.engine.execution.StepOutcomes;

/**
 * A failed exchange with an upstream MCP server: transport, HTTP, JSON-RPC or tool-level failure.
 *
 * <p>Carries no status code: exposers currently report every consumed-call failure the same way,
 * so a status here would suggest a mapping that does not exist. It carries the
 * protocol-neutral outcome of the call instead ({@link StepOutcomes}), which a call step's
 * success criteria read (#1168): {@code failure} unless the failure says more, such as an
 * upstream answering 404 ({@code not-found}) or 429 ({@code throttled}), or no answer at all
 * ({@code unreachable}).</p>
 */
public class McpClientException extends RuntimeException
        implements StepOutcomes.Reported {

    private static final long serialVersionUID = 1L;

    private final String outcome;

    public McpClientException(String message) {
        this(message, (Throwable) null, StepOutcomes.FAILURE);
    }

    public McpClientException(String message, Throwable cause) {
        this(message, cause, StepOutcomes.FAILURE);
    }

    public McpClientException(String message, String outcome) {
        this(message, null, outcome);
    }

    public McpClientException(String message, Throwable cause, String outcome) {
        super(message, cause);
        this.outcome = outcome;
    }

    @Override
    public String stepOutcome() {
        return outcome;
    }
}
