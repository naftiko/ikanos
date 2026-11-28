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
 * must stay within it.</p>
 *
 * <p>With {@code hosted: true}, a tool that takes a file and whose flow declares no
 * {@code upload} also takes it out of band: the {@code upload_required} target is a path on the
 * adapter itself ({@code <MCP address>/files/<token>}, {@code credentials: include}), the bytes
 * are kept for the retry within {@code quota}, and the target accepts its {@code PUT} for
 * {@code ttl}. Every field is optional; the getters with a default return it.</p>
 */
public class McpFileUploadsSpec {

    /** Default {@code maxInlineBytes}: the inline files of one request, in total. */
    public static final String DEFAULT_MAX_INLINE_BYTES = "1MiB";

    /** Default {@code quota}: the bytes of all the hosted uploads kept at once. */
    public static final String DEFAULT_QUOTA = "100MiB";

    /** Default {@code ttl}, an ISO 8601 duration: five minutes. */
    public static final String DEFAULT_TTL = "PT5M";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String maxInlineBytes;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Boolean hosted;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String quota;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String ttl;

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

    /** @return the declared {@code hosted} flag, or {@code null} */
    public Boolean getHosted() {
        return hosted;
    }

    /** @return whether the adapter hosts uploads itself: {@code false} unless declared */
    public boolean isHosted() {
        return Boolean.TRUE.equals(hosted);
    }

    public void setHosted(Boolean hosted) {
        this.hosted = hosted;
    }

    /** @return the declared storage quota sized string (e.g. {@code "500MiB"}), or {@code null} */
    public String getQuota() {
        return quota;
    }

    /** @return the declared storage quota, or {@value #DEFAULT_QUOTA} */
    public String quotaOrDefault() {
        return quota != null ? quota : DEFAULT_QUOTA;
    }

    public void setQuota(String quota) {
        this.quota = quota;
    }

    /** @return the declared target lifetime, an ISO 8601 duration, or {@code null} */
    public String getTtl() {
        return ttl;
    }

    /** @return the declared target lifetime, or {@value #DEFAULT_TTL} */
    public String ttlOrDefault() {
        return ttl != null ? ttl : DEFAULT_TTL;
    }

    public void setTtl(String ttl) {
        this.ttl = ttl;
    }
}
