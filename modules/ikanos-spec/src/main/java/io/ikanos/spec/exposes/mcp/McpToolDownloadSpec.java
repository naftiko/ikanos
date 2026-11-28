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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Tool Download Specification Element (experimental).
 *
 * <p>Declares that a tool's mapped output describes a file the consumed API already serves, such
 * as a presigned storage URL. Each field names a top-level property of the tool's mapped JSON
 * output. When the client declares {@code download}, the engine returns that file as a download
 * target with {@code credentials: omit} rather than the mapped output.</p>
 */
public class McpToolDownloadSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String url;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String size;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String mimeType;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String name;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String expiresAt;

    public McpToolDownloadSpec() {}

    /** @return the output property that carries the absolute https URL of the file */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** @return the output property that carries the file size in bytes */
    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    /** @return the output property that carries the media type, or {@code null} */
    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    /** @return the output property that carries the file name, or {@code null} */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** @return the output property that carries the link expiry timestamp, or {@code null} */
    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }
}
