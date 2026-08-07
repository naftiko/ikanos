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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ikanos.exception.ProcessorException;

import java.util.Objects;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * A post-processor that appends cache data to responses.
 */
public class CacheDataAppender implements DispatchPostProcessor {

    private final int ttlMs;
    private final String cacheScope;

    public CacheDataAppender(Integer ttlMs, String cacheScope) {
        this.ttlMs = Objects.requireNonNullElse(ttlMs, 5 * 60 * 1000); // 5min by default
        this.cacheScope = Objects.requireNonNullElse(cacheScope, "private"); // 'private' by default
    }

    @Override
    public void apply(ObjectNode response) throws ProcessorException {
        if (response.get("result") instanceof ObjectNode result) {
            result.put("ttlMs", ttlMs);
            result.put("cacheScope", cacheScope);
        } else {
            throw new ProcessorException("Internal error: expected a result tag", INTERNAL_ERROR,
                    buildJsonRpcError(null, INTERNAL_ERROR.getCode(), "Internal error: expected a result tag"));
        }
    }
}
