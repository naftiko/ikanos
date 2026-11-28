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
 * MCP File Downloads Specification Element (experimental).
 *
 * <p>When a client declares {@code download}, a binary tool result larger than
 * {@code inlineThreshold} is served from a short-lived link on the adapter for {@code ttl}
 * instead of inline base64. Every field is optional; the getters with a default return it.</p>
 */
public class McpFileDownloadsSpec {

    /** Default {@code inlineThreshold}: larger binary results become download targets. */
    public static final String DEFAULT_INLINE_THRESHOLD = "1MiB";

    /** Default {@code ttl}, an ISO 8601 duration: five minutes. */
    public static final String DEFAULT_TTL = "PT5M";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String inlineThreshold;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String ttl;

    public McpFileDownloadsSpec() {}

    /** @return the declared threshold sized string (e.g. {@code "1MiB"}), or {@code null} */
    public String getInlineThreshold() {
        return inlineThreshold;
    }

    /** @return the declared threshold, or {@value #DEFAULT_INLINE_THRESHOLD} */
    public String inlineThresholdOrDefault() {
        return inlineThreshold != null ? inlineThreshold : DEFAULT_INLINE_THRESHOLD;
    }

    public void setInlineThreshold(String inlineThreshold) {
        this.inlineThreshold = inlineThreshold;
    }

    /** @return the declared link lifetime, an ISO 8601 duration, or {@code null} */
    public String getTtl() {
        return ttl;
    }

    /** @return the declared link lifetime, or {@value #DEFAULT_TTL} */
    public String ttlOrDefault() {
        return ttl != null ? ttl : DEFAULT_TTL;
    }

    public void setTtl(String ttl) {
        this.ttl = ttl;
    }
}
