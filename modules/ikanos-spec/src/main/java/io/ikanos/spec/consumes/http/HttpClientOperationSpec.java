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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.InputParameterMapDeserializer;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OperationSpec;

/**
 * HTTP Operation Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code body} field is held in an {@link AtomicReference}. This satisfies SonarQube rule
 * {@code java:S3077}.
 *
 * <h2>Query parameter names</h2>
 * {@code queryParameters} names are expanded into {@code in: query} input parameters (see
 * {@link QueryParameterNames}), whichever of {@code inputParameters} and {@code queryParameters}
 * is set first, so {@link #getInputParameters()} returns both forms. The list itself is read-only
 * for serialization: the expanded entries are written as {@code inputParameters}.
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

    /** The {@code inputParameters} as declared, before the query parameter names are added. */
    private final AtomicReference<Map<String, InputParameterSpec>> declaredInputParameters =
            new AtomicReference<>(Map.of());

    private final List<String> queryParameters = new CopyOnWriteArrayList<>();

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

    @Override
    @JsonDeserialize(using = InputParameterMapDeserializer.class)
    public void setInputParameters(Map<String, InputParameterSpec> params) {
        declaredInputParameters.set(new LinkedHashMap<>(params != null ? params : Map.of()));
        super.setInputParameters(
                QueryParameterNames.expand(declaredInputParameters.get(), queryParameters));
    }

    /** The {@code queryParameters} list, as declared (#987). */
    @JsonProperty(value = "queryParameters", access = JsonProperty.Access.WRITE_ONLY)
    public List<String> getQueryParameters() {
        return List.copyOf(queryParameters);
    }

    public void setQueryParameters(List<String> names) {
        queryParameters.clear();
        if (names != null) {
            names.stream().filter(java.util.Objects::nonNull).forEach(queryParameters::add);
        }
        super.setInputParameters(
                QueryParameterNames.expand(declaredInputParameters.get(), queryParameters));
    }

    /**
     * @param where the operation, for the messages
     * @return one message per {@code queryParameters} name also declared in this operation's
     *         {@code inputParameters} or listed twice
     */
    public List<String> queryParameterErrors(String where) {
        return QueryParameterNames.errors(declaredInputParameters.get(), queryParameters, where);
    }

}
