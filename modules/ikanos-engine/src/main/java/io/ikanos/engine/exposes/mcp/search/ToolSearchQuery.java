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
package io.ikanos.engine.exposes.mcp.search;

import java.util.Objects;
import java.util.Set;

/**
 * One tool search, as a {@link ToolSearchIndex} receives it.
 *
 * <p>The text is plain words written by a model. An index must never interpret it as query
 * syntax: characters such as {@code :}, {@code *}, {@code "} or words such as {@code AND} are
 * search terms, not operators.</p>
 *
 * @param text what the model wants to do, in natural language or keywords
 * @param anyTags only return tools carrying at least one of these tags; empty means any tool
 * @param readOnlyOnly only return tools that declare that they do not modify state
 * @param limit the maximum number of tool names to return, at least 1
 */
public record ToolSearchQuery(String text, Set<String> anyTags, boolean readOnlyOnly, int limit) {

    public ToolSearchQuery {
        Objects.requireNonNull(text, "text");
        anyTags = anyTags == null ? Set.of() : Set.copyOf(anyTags);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
    }
}
