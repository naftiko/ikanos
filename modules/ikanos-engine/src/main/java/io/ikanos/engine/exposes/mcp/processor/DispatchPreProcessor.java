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
import io.ikanos.exception.ProcessorException;

/**
 * Common interface for pre-processors that must run
 * on requests before applying the dispatch.
 */
public interface DispatchPreProcessor {

    /**
     * Run the processor.
     * @param request the request's body.
     * @throws ProcessorException when an error occurs while running the post-processor.
     */
    void apply(JsonNode request) throws ProcessorException;
}
