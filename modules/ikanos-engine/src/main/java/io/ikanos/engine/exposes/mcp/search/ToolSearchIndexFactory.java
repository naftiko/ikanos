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
 * Service provider interface for MCP tool search, discovered with {@link java.util.ServiceLoader}.
 *
 * <p>The engine ships no implementation. The optional {@code ikanos-tool-search} module provides
 * one backed by Apache Lucene; an endpoint offers deferred tool advertisement only when a provider
 * is on the classpath. Keeping the implementation out of the engine keeps Lucene out of
 * applications that embed it and already have their own tool search.</p>
 *
 * <p>Implementations need a public no-argument constructor without side effects, and are
 * registered in {@code META-INF/services/io.ikanos.engine.exposes.mcp.search.ToolSearchIndexFactory}.</p>
 */
public interface ToolSearchIndexFactory {

    /** @return a short name for the implementation, such as {@code lucene} */
    String type();

    /**
     * Builds an index over the given tools. Called at most once per endpoint, on the first search.
     *
     * @param documents the searchable tools, in authored order
     * @return an immutable, thread-safe index
     */
    ToolSearchIndex create(List<ToolSearchDocument> documents);
}
