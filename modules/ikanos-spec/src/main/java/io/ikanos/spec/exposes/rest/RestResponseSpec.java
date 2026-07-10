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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single HTTP status-code entry in a REST operation's response contract
 * ({@code responses.<code>}). Mirrors the OpenAPI Response Object subset Ikanos exports.
 *
 * <p>See {@code blueprints/capability-binary-content.md} §7.1.</p>
 *
 * <h2>Thread safety</h2>
 * The {@code content} map is a synchronized {@link LinkedHashMap} (media-type order preserved)
 * wrapped in an {@link AtomicReference}; {@code description} is an {@link AtomicReference}
 * (SonarQube {@code java:S3077}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestResponseSpec {

    private final AtomicReference<String> description = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final AtomicReference<Map<String, RestResponseContentSpec>> content =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    public RestResponseSpec() {
        // Jackson
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, RestResponseContentSpec> getContent() {
        return content.get();
    }

    public void setContent(Map<String, RestResponseContentSpec> content) {
        Map<String, RestResponseContentSpec> snapshot = Collections.synchronizedMap(
                new LinkedHashMap<>(content != null ? content : Map.of()));
        this.content.set(snapshot);
    }
}
