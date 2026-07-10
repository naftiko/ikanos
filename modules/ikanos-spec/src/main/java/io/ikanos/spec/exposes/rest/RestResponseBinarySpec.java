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
package io.ikanos.spec.exposes.rest;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Shorthand for a single successful binary response on a REST operation
 * ({@code responseBinary}). Normalized into the full {@code responses} structure at spec load.
 * Mutually exclusive with {@code responses}.
 *
 * <p>See {@code blueprints/capability-binary-content.md} §7.2.</p>
 *
 * <h2>Thread safety</h2>
 * Scalar fields are held in {@link AtomicReference} (SonarQube {@code java:S3077}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestResponseBinarySpec {

    /** Default HTTP status code for the binary response when {@code status} is omitted. */
    public static final int DEFAULT_STATUS = 200;

    private final AtomicReference<Integer> status = new AtomicReference<>();

    private final AtomicReference<String> mediaType = new AtomicReference<>();

    private final AtomicReference<String> description = new AtomicReference<>();

    public RestResponseBinarySpec() {
        // Jackson
    }

    /** @return the declared status code, or {@link #DEFAULT_STATUS} (200) when omitted */
    public int getStatusOrDefault() {
        Integer s = status.get();
        return s != null ? s : DEFAULT_STATUS;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getStatus() {
        return status.get();
    }

    public void setStatus(Integer status) {
        this.status.set(status);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMediaType() {
        return mediaType.get();
    }

    public void setMediaType(String mediaType) {
        this.mediaType.set(mediaType);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }
}
