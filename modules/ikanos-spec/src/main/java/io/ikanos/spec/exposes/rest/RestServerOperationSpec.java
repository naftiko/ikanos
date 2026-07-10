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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.OperationSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepMapDeserializer;
import io.ikanos.spec.util.OperationStepSpec;
import io.ikanos.spec.util.StepOutputMappingSpec;

/**
 * API Operation Specification Element.
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code with} parameter map is
 * stored as an immutable snapshot. {@code steps} is a synchronized {@link LinkedHashMap}
 * preserving YAML order. {@code mappings} uses {@link CopyOnWriteArrayList}. This satisfies
 * SonarQube rule {@code java:S3077}.
 */
public class RestServerOperationSpec extends OperationSpec {

    private final AtomicReference<ServerCallSpec> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();
    private final AtomicReference<String> ref = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = OperationStepMapDeserializer.class)
    private final Map<String, OperationStepSpec> steps =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final CopyOnWriteArrayList<StepOutputMappingSpec> mappings = new CopyOnWriteArrayList<>();

    /**
     * Explicit per-status-code response contract, keyed by HTTP status code (e.g. {@code "200"},
     * {@code "404"}). Drives binary response handling and OpenAPI export. Mutually exclusive with
     * {@link #responseBinary}. See {@code blueprints/capability-binary-content.md} §7.1.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final AtomicReference<Map<String, RestResponseSpec>> responses =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    /**
     * Shorthand for a single successful binary response, normalized into {@link #responses} via
     * {@link #getEffectiveResponses()}. Mutually exclusive with {@link #responses}. See
     * {@code blueprints/capability-binary-content.md} §7.2.
     */
    private final AtomicReference<RestResponseBinarySpec> responseBinary = new AtomicReference<>();

    public RestServerOperationSpec() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String display) {
        this(parentResource, method, name, display, null, null, null, null, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String display, String description, String outputRawFormat, ServerCallSpec call) {
        this(parentResource, method, name, display, description, outputRawFormat, null, call, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String display, String description, String outputRawFormat, String outputSchema, ServerCallSpec call) {
        this(parentResource, method, name, display, description, outputRawFormat, outputSchema, call, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String display, String description, String outputRawFormat, String outputSchema, ServerCallSpec call, Map<String, Object> with) {
        super(parentResource, method, name, display, description, outputRawFormat, outputSchema);
        this.call.set(call);
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

    public Map<String, OperationStepSpec> getSteps() { return steps; }

    public void setSteps(Map<String, OperationStepSpec> steps) {
        if (steps == null) return;
        synchronized (this.steps) { this.steps.clear(); this.steps.putAll(steps); }
    }

    public CopyOnWriteArrayList<StepOutputMappingSpec> getMappings() { return mappings; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ServerCallSpec getCall() { return call.get(); }
    public void setCall(ServerCallSpec call) { this.call.set(call); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getWith() { return with.get(); }
    public void setWith(Map<String, Object> with) { this.with.set(with != null ? Map.copyOf(with) : null); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getRef() { return ref.get(); }
    public void setRef(String ref) { this.ref.set(ref); }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, RestResponseSpec> getResponses() { return responses.get(); }

    public void setResponses(Map<String, RestResponseSpec> responses) {
        Map<String, RestResponseSpec> snapshot = Collections.synchronizedMap(
                new LinkedHashMap<>(responses != null ? responses : Map.of()));
        this.responses.set(snapshot);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public RestResponseBinarySpec getResponseBinary() { return responseBinary.get(); }

    public void setResponseBinary(RestResponseBinarySpec responseBinary) {
        this.responseBinary.set(responseBinary);
    }

    /**
     * Resolve the effective response contract, normalizing the {@code responseBinary} shorthand
     * into the full {@code responses} structure (§7.2). When {@code responses} is declared it is
     * returned as-is; when only {@code responseBinary} is declared it is expanded into a single
     * status entry with a {@code binary: true} content for its media type; when neither is declared
     * an empty map is returned.
     *
     * @return the normalized status-code → {@link RestResponseSpec} map; never {@code null}
     */
    public Map<String, RestResponseSpec> getEffectiveResponses() {
        Map<String, RestResponseSpec> declared = responses.get();
        if (declared != null && !declared.isEmpty()) {
            return declared;
        }

        RestResponseBinarySpec shorthand = responseBinary.get();
        if (shorthand == null || shorthand.getMediaType() == null) {
            return Map.of();
        }

        RestResponseSpec responseSpec = new RestResponseSpec();
        responseSpec.setDescription(shorthand.getDescription());
        Map<String, RestResponseContentSpec> contentMap = new LinkedHashMap<>();
        contentMap.put(shorthand.getMediaType(), new RestResponseContentSpec(Boolean.TRUE));
        responseSpec.setContent(contentMap);

        Map<String, RestResponseSpec> normalized = new LinkedHashMap<>();
        normalized.put(String.valueOf(shorthand.getStatusOrDefault()), responseSpec);
        return normalized;
    }

    /**
     * Find the first binary media type declared across all status codes in the effective response
     * contract (§7). Returns the media type string (e.g. {@code "image/jpeg"}) of the first
     * {@code content} entry whose {@code binary} flag is true, or empty when the operation declares
     * no binary response.
     *
     * @return the declared binary media type, or {@link Optional#empty()} when none is declared
     */
    public Optional<String> findBinaryResponseMediaType() {
        for (RestResponseSpec responseSpec : getEffectiveResponses().values()) {
            Map<String, RestResponseContentSpec> content = responseSpec.getContent();
            if (content == null) {
                continue;
            }
            for (Map.Entry<String, RestResponseContentSpec> entry : content.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isBinary()) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /** @return {@code true} iff the effective response contract declares any binary media type */
    public boolean hasBinaryResponse() {
        return findBinaryResponseMediaType().isPresent();
    }
}

