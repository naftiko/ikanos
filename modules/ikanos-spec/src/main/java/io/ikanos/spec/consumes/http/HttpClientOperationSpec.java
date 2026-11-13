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
package io.ikanos.spec.consumes.http;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.ikanos.spec.OperationSpec;

/**
 * HTTP Operation Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code body} field is held in an {@link AtomicReference}. This satisfies SonarQube rule
 * {@code java:S3077}.
 */
public class HttpClientOperationSpec extends OperationSpec {

    /**
     * Request body definition. Accepts either:
     * <ul>
     *   <li>a plain {@link String} — treated as a raw Mustache template sent as
     *       {@code application/json} (legacy form)</li>
     *   <li>a {@link java.util.Map} with {@code type} and {@code data} fields — the structured
     *       {@code RequestBody} object defined in the Ikanos specification</li>
     * </ul>
     */
    private final AtomicReference<Object> body = new AtomicReference<>();

    /**
     * Contract-level response media type. Independent of {@code outputRawFormat}: it applies
     * equally to parsed responses (JSON, XML, YAML, TOML, INI) and to binary responses. It
     * overrides the upstream {@code Content-Type} for advertised media types but never changes how
     * the entity is parsed. See {@code blueprints/capability-binary-content.md} §4.3.1 / §4.3.2.
     */
    private final AtomicReference<String> outputMediaType = new AtomicReference<>();

    /**
     * Per-operation override of the adapter-level {@code maxBinarySize}. Accepts sized strings such
     * as {@code "512KiB"}, {@code "10MiB"}, {@code "1GiB"} (pattern
     * {@code ^\d+(\.\d+)?(B|KiB|MiB|GiB)?$}). See {@code blueprints/capability-binary-content.md}
     * §4.7 / §5.1.
     */
    private final AtomicReference<String> maxBinarySize = new AtomicReference<>();

    public HttpClientOperationSpec() {
        this(null, null, null, null, null, null, null);
    }

    public HttpClientOperationSpec(HttpClientResourceSpec parentResource, String method, String name, String display) {
        this(parentResource, method, name, display, null, null, null);
    }

    public HttpClientOperationSpec(HttpClientResourceSpec parentResource, String method, String name, String display, String description, Object body, String outputRawFormat) {
        this(parentResource, method, name, display, description, body, outputRawFormat, null);
    }

    public HttpClientOperationSpec(HttpClientResourceSpec parentResource, String method, String name, String display, String description, Object body, String outputRawFormat, String outputSchema) {
        super(parentResource, method, name, display, description, outputRawFormat, outputSchema);
        this.body.set(body);
    }

    public Object getBody() {
        return body.get();
    }

    public void setBody(Object body) {
        this.body.set(body);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getOutputMediaType() {
        return outputMediaType.get();
    }

    public void setOutputMediaType(String outputMediaType) {
        this.outputMediaType.set(outputMediaType);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMaxBinarySize() {
        return maxBinarySize.get();
    }

    public void setMaxBinarySize(String maxBinarySize) {
        this.maxBinarySize.set(maxBinarySize);
    }

}
