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
 * MCP File Uploads Specification Element (experimental).
 *
 * <p>File inputs ({@code type: file}) sent inline in a {@code tools/call}: {@code server/discover}
 * advertises {@code maxInlineBytes}, and the decoded size of all the inline files of one request
 * must stay within it. Out-of-band uploads are not supported yet.</p>
 */
public class McpFileUploadsSpec {

    /** Default {@code maxInlineBytes}: the inline files of one request, in total. */
    public static final String DEFAULT_MAX_INLINE_BYTES = "1MiB";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String maxInlineBytes;

    public McpFileUploadsSpec() {}

    /** @return the declared limit sized string (e.g. {@code "1MiB"}), or {@code null} */
    public String getMaxInlineBytes() {
        return maxInlineBytes;
    }

    /** @return the declared limit, or {@value #DEFAULT_MAX_INLINE_BYTES} */
    public String maxInlineBytesOrDefault() {
        return maxInlineBytes != null ? maxInlineBytes : DEFAULT_MAX_INLINE_BYTES;
    }

    public void setMaxInlineBytes(String maxInlineBytes) {
        this.maxInlineBytes = maxInlineBytes;
    }
}
