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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The payload contract for a single media type within a REST operation's response
 * ({@code responses.<code>.content.<mediaType>}).
 *
 * <p>Either {@code binary: true} (raw bytes, exported as {@code type: string, format: binary}) or
 * an inline structured {@code schema}. See {@code blueprints/capability-binary-content.md} §7.1 /
 * §7.4.</p>
 *
 * <h2>Thread safety</h2>
 * Scalar fields are held in {@link AtomicReference} (SonarQube {@code java:S3077}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestResponseContentSpec {

    private final AtomicReference<Boolean> binary = new AtomicReference<>();

    private final AtomicReference<Map<String, Object>> schema = new AtomicReference<>();

    public RestResponseContentSpec() {
        // Jackson
    }

    public RestResponseContentSpec(Boolean binary) {
        this.binary.set(binary);
    }

    /**
     * @return {@code true} when this media type returns raw bytes; {@code null}/{@code false}
     *         otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getBinary() {
        return binary.get();
    }

    public void setBinary(Boolean binary) {
        this.binary.set(binary);
    }

    /** @return {@code true} iff {@link #getBinary()} is non-null and {@code true} */
    public boolean isBinary() {
        return Boolean.TRUE.equals(binary.get());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getSchema() {
        return schema.get();
    }

    public void setSchema(Map<String, Object> schema) {
        this.schema.set(schema);
    }
}
