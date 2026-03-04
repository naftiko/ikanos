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
package io.naftiko.spec.exposes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.naftiko.spec.OperationSpec;

/**
 * API Operation Specification Element
 */
public class ApiServerOperationSpec extends OperationSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ApiServerCallSpec call;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OperationStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    public ApiServerOperationSpec() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public ApiServerOperationSpec(ApiServerResourceSpec parentResource, String method, String name, String label) {
        this(parentResource, method, name, label, null, null, null, null, null);
    }

    public ApiServerOperationSpec(ApiServerResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, ApiServerCallSpec call) {
        this(parentResource, method, name, label, description, outputRawFormat, null, call, null);
    }

    public ApiServerOperationSpec(ApiServerResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, String outputSchema, ApiServerCallSpec call) {
        this(parentResource, method, name, label, description, outputRawFormat, outputSchema, call, null);
    }

    public ApiServerOperationSpec(ApiServerResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, String outputSchema, ApiServerCallSpec call, Map<String, Object> with) {
        super(parentResource, method, name, label, description, outputRawFormat, outputSchema);
        this.call = call;
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
        this.steps = new CopyOnWriteArrayList<>();
    }

    public List<OperationStepSpec> getSteps() {
        return steps;
    }

    public ApiServerCallSpec getCall() {
        return call;
    }

    public void setCall(ApiServerCallSpec call) {
        this.call = call;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

}

