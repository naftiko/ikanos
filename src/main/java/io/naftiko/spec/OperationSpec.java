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
package io.naftiko.spec;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Operation Specification Element.
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference} so that fluent builders and
 * Control-port runtime edits can replace values atomically while engine threads read them.
 * The {@code inputParameters} and {@code outputParameters} lists are {@link CopyOnWriteArrayList}s.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
public class OperationSpec {

    @JsonIgnore
    private final AtomicReference<ResourceSpec> parentResource = new AtomicReference<>();

    private final AtomicReference<String> method = new AtomicReference<>();

    private final AtomicReference<String> name = new AtomicReference<>();

    private final AtomicReference<String> label = new AtomicReference<>();

    private final AtomicReference<String> description = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    private final AtomicReference<String> outputRawFormat = new AtomicReference<>();

    private final AtomicReference<String> outputSchema = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OutputParameterSpec> outputParameters;

    public OperationSpec() {
        this(null, null, null, null, null, null, null);
    }

    public OperationSpec(ResourceSpec parentResource, String method, String name, String label) {
        this(parentResource, method, name, label, null, null, null);
    }

    public OperationSpec(ResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat) {
        this(parentResource, method, name, label, description, outputRawFormat, null);
    }

    public OperationSpec(ResourceSpec parentResource, String method, String name, String label, String description, String outputRawFormat, String outputSchema) {
        this.parentResource.set(parentResource);
        this.method.set(method);
        this.name.set(name);
        this.label.set(label);
        this.description.set(description);
        this.outputRawFormat.set(outputRawFormat);
        this.outputSchema.set(outputSchema);
        this.inputParameters = new CopyOnWriteArrayList<>();
        this.outputParameters = new CopyOnWriteArrayList<>();
    }

    public ResourceSpec getParentResource() {
        return parentResource.get();
    }

    /**
     * Sets the parent resource for this operation.
     * This is called during deserialization to establish the parent-child relationship.
     *
     * @param parentResource the parent ResourceSpec
     */
    public void setParentResource(ResourceSpec parentResource) {
        this.parentResource.set(parentResource);
    }

    public String getMethod() {
        return method.get();
    }

    public void setMethod(String method) {
        this.method.set(method);
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public String getLabel() {
        return label.get();
    }

    public void setLabel(String label) {
        this.label.set(label);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getOutputRawFormat() {
        return outputRawFormat.get();
    }

    public void setOutputRawFormat(String outputRawFormat) {
        this.outputRawFormat.set(outputRawFormat);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getOutputSchema() {
        return outputSchema.get();
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema.set(outputSchema);
    }

    public List<OutputParameterSpec> getOutputParameters() {
        return outputParameters;
    }

}