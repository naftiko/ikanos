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
package io.ikanos.spec.exposes.mcp;

import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Files Specification Element (experimental).
 *
 * <p>Opts an MCP adapter into the draft MCP files extension ({@code io.modelcontextprotocol/files}).
 * An adapter that declares this block advertises the extension in {@code server/discover}; one
 * that does not behaves as before. Only downloads are supported for now.</p>
 */
public class McpFilesSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final AtomicReference<McpFileDownloadsSpec> downloads = new AtomicReference<>();

    public McpFilesSpec() {}

    /** @return the download settings, or {@code null} when the block omits them */
    public McpFileDownloadsSpec getDownloads() {
        return downloads.get();
    }

    public void setDownloads(McpFileDownloadsSpec downloads) {
        this.downloads.set(downloads);
    }

    /** @return the download settings, defaults included when the block omits them */
    public McpFileDownloadsSpec downloadsOrDefault() {
        McpFileDownloadsSpec declared = downloads.get();
        return declared != null ? declared : new McpFileDownloadsSpec();
    }
}
