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
package io.naftiko.spec.exposes.rest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.naftiko.spec.OperationSpec;
import io.naftiko.spec.exposes.ServerCallSpec;
import io.naftiko.spec.util.OperationStepSpec;
import io.naftiko.spec.util.StepOutputMappingSpec;

/**
 * API Operation Specification Element.
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code with} parameter map is
 * stored as an immutable snapshot. List fields use {@link CopyOnWriteArrayList}. This
 * satisfies SonarQube rule {@code java:S3077}.
 */
public class RestServerOperationSpec extends OperationSpec {

    private final AtomicReference<ServerCallSpec> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();
    private final AtomicReference<String> ref = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OperationStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<StepOutputMappingSpec> mappings;

    public RestServerOperationSpec() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String label) {
        this(parentResource, method, name, label, null, null, null, null, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, ServerCallSpec call) {
        this(parentResource, method, name, label, description, outputRawFormat, null, call, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, String outputSchema, ServerCallSpec call) {
        this(parentResource, method, name, label, description, outputRawFormat, outputSchema, call, null);
    }

    public RestServerOperationSpec(RestServerResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, String outputSchema, ServerCallSpec call, Map<String, Object> with) {
        super(parentResource, method, name, label, description, outputRawFormat, outputSchema);
        this.call.set(call);
        this.with.set(with != null ? Map.copyOf(with) : null);
        this.steps = new CopyOnWriteArrayList<>();
        this.mappings = new CopyOnWriteArrayList<>();
    }

    public List<OperationStepSpec> getSteps() {
        return steps;
    }

    public List<StepOutputMappingSpec> getMappings() {
        return mappings;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ServerCallSpec getCall() {
        return call.get();
    }

    public void setCall(ServerCallSpec call) {
        this.call.set(call);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getWith() {
        return with.get();
    }

    public void setWith(Map<String, Object> with) {
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getRef() {
        return ref.get();
    }

    public void setRef(String ref) {
        this.ref.set(ref);
    }

}

