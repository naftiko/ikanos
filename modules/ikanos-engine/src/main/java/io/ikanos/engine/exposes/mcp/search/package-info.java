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

/**
 * MCP tool search: the {@link io.ikanos.engine.exposes.mcp.search.ToolSearchIndexFactory} service
 * provider interface and its discovery.
 *
 * <p>This package holds no search implementation and no Lucene type. The optional
 * {@code ikanos-tool-search} module implements the interface with Apache Lucene and is found with
 * {@link java.util.ServiceLoader}, as {@code ikanos-tunnel-ziti} is for tunnels.</p>
 */
package io.ikanos.engine.exposes.mcp.search;
