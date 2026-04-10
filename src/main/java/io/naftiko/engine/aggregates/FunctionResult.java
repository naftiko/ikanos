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
package io.naftiko.engine.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import io.naftiko.engine.exposes.OperationStepExecutor;

/**
 * Transport-neutral result of executing an aggregate function.
 *
 * <p>Adapters (MCP, REST) convert this into their protocol-specific response format.</p>
 */
public class FunctionResult {

    /** The last HTTP handling context (simple call or last orchestrated step). May be null in mock mode. */
    public final OperationStepExecutor.HandlingContext lastContext;

    /** Resolved step mappings output (orchestrated mode with mappings). May be null. */
    public final String mappedOutput;

    /** Mock output built from outputParameter value fields. May be null. */
    public final JsonNode mockOutput;

    FunctionResult(OperationStepExecutor.HandlingContext lastContext, String mappedOutput,
            JsonNode mockOutput) {
        this.lastContext = lastContext;
        this.mappedOutput = mappedOutput;
        this.mockOutput = mockOutput;
    }

    /** True when the result was produced by mock mode (no call, no steps). */
    public boolean isMock() {
        return mockOutput != null;
    }

    /** True when the result includes mapped step output. */
    public boolean hasMappedOutput() {
        return mappedOutput != null;
    }

}
