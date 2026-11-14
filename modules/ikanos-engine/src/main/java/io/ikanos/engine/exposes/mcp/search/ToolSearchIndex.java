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

import java.util.List;

/**
 * A search index over the tools of one MCP endpoint, built once by a
 * {@link ToolSearchIndexFactory} and queried concurrently.
 */
public interface ToolSearchIndex {

    /**
     * Returns the names of the best-matching tools, best first.
     *
     * <p>The same query against the same index must always return the same names in the same
     * order: the result enters the conversation, and a stable result keeps the following turns
     * cacheable. Implementations break score ties by tool name.</p>
     *
     * @param query the search
     * @return at most {@code query.limit()} tool names; empty when nothing matches
     */
    List<String> search(ToolSearchQuery query);
}
