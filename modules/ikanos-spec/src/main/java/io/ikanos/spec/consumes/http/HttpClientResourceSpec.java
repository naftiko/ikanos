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

import java.util.Collections;
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
import io.ikanos.spec.ResourceSpec;

/**
 * HTTP Resource Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code operations} map is a synchronized {@link LinkedHashMap} preserving YAML insertion
 * order (critical for call-reference resolution). This satisfies SonarQube rule {@code java:S3077}.
 *
 * <h2>Query parameter names</h2>
 * {@code queryParameters} names are expanded into {@code in: query} input parameters (see
 * {@link QueryParameterNames}), whichever of {@code inputParameters} and {@code queryParameters}
 * is set first, so {@link #getInputParameters()} returns both forms. The list itself is read-only
 * for serialization: the expanded entries are written as {@code inputParameters}.
 */
public class HttpClientResourceSpec extends ResourceSpec {

    @JsonDeserialize(using = HttpClientOperationMapDeserializer.class)
    private final Map<String, HttpClientOperationSpec> operations =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** The {@code inputParameters} as declared, before the query parameter names are added. */
    private final AtomicReference<Map<String, InputParameterSpec>> declaredInputParameters =
            new AtomicReference<>(Map.of());

    private final List<String> queryParameters = new CopyOnWriteArrayList<>();

    public HttpClientResourceSpec() {
        this(null, null, null, null);
    }

    public HttpClientResourceSpec(String path, String name, String display) {
        this(path, name, display, null);
    }

    public HttpClientResourceSpec(String path, String name, String display, String description) {
        super(path, name, display, description);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, HttpClientOperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, HttpClientOperationSpec> operations) {
        if (operations == null) return;
        synchronized (this.operations) {
            this.operations.clear();
            operations.forEach((name, op) -> {
                op.setParentResource(this);
                this.operations.put(name, op);
            });
        }
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
     * @param where the resource, for the messages
     * @return one message per {@code queryParameters} name also declared in this resource's
     *         {@code inputParameters} or listed twice
     */
    public List<String> queryParameterErrors(String where) {
        return QueryParameterNames.errors(declaredInputParameters.get(), queryParameters, where);
    }
}
