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
import java.util.Objects;

/**
 * The searchable text of one MCP tool, handed to a {@link ToolSearchIndexFactory}.
 *
 * <p>Providers see only this record, never a spec or {@code McpSchema} type, so an index
 * implementation depends on this small API alone.</p>
 *
 * @param name the tool name, unique within the endpoint
 * @param title the tool's display title, or {@code null}
 * @param description the tool description, or {@code null}
 * @param tags the tool's declared tags
 * @param parameters the names and descriptions of the tool's inputs, as one text
 * @param readOnly whether the tool declares that it does not modify state
 */
public record ToolSearchDocument(String name, String title, String description,
        List<String> tags, String parameters, boolean readOnly) {

    public ToolSearchDocument {
        Objects.requireNonNull(name, "name");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
