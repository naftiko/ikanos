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

import tools.jackson.databind.node.ObjectNode;
import io.ikanos.exception.ProcessorException;

import java.util.Objects;

import static io.ikanos.engine.exposes.mcp.model.JsonRpcError.INTERNAL_ERROR;
import static io.ikanos.engine.util.JsonRpcResponseBuilder.buildJsonRpcError;

/**
 * A post-processor that appends the adapter's cache hints to a result. A hint the handler already
 * set (a resource's own {@code ttlMs} or {@code cacheScope} on {@code resources/read}) is kept.
 */
public class CacheDataAppender implements DispatchPostProcessor {

    private final int ttlMs;
    private final String cacheScope;

    public CacheDataAppender(Integer ttlMs, String cacheScope) {
        this(new CacheHints(Objects.requireNonNullElse(ttlMs, CacheHints.DEFAULT_TTL_MS),
                Objects.requireNonNullElse(cacheScope, CacheHints.DEFAULT_SCOPE)));
    }

    public CacheDataAppender(CacheHints hints) {
        this.ttlMs = hints.ttlMs();
        this.cacheScope = hints.cacheScope();
    }

    @Override
    public void apply(ObjectNode response) throws ProcessorException {
        if (response.get("result") instanceof ObjectNode result) {
            if (!result.has("ttlMs")) {
                result.put("ttlMs", ttlMs);
            }
            if (!result.has("cacheScope")) {
                result.put("cacheScope", cacheScope);
            }
        } else {
            throw new ProcessorException("Internal error: expected a result tag", INTERNAL_ERROR,
                    buildJsonRpcError(null, INTERNAL_ERROR.getCode(), "Internal error: expected a result tag"));
        }
    }
}
