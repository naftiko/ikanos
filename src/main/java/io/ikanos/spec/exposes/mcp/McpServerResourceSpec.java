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
package io.ikanos.spec.exposes.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.exposes.ServerCallSpec;
import io.ikanos.spec.util.OperationStepSpec;

/**
 * MCP Resource Specification Element.
 *
 * <p>Defines an MCP resource that exposes data agents can read. Two source types are supported:</p>
 * <ul>
 *   <li><b>Dynamic</b> ({@code call}/{@code steps}): backed by consumed HTTP operations — same
 *       orchestration model as tools.</li>
 *   <li><b>Static</b> ({@code location}): served from local files identified by a
 *       {@code file:///} URI.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code with} parameter map is
 * stored as an immutable snapshot. List fields use {@link CopyOnWriteArrayList}. This
 * satisfies SonarQube rule {@code java:S3077}.
 */
public class McpServerResourceSpec {

    private final AtomicReference<String> name = new AtomicReference<>();
    private final AtomicReference<String> label = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<String> mimeType = new AtomicReference<>();
    private final AtomicReference<ServerCallSpec> call = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();
    private final AtomicReference<String> location = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OperationStepSpec> steps;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<OutputParameterSpec> outputParameters;

    public McpServerResourceSpec() {
        this.steps = new CopyOnWriteArrayList<>();
        this.outputParameters = new CopyOnWriteArrayList<>();
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLabel() {
        return label.get();
    }

    public void setLabel(String label) {
        this.label.set(label);
    }

    public String getUri() {
        return uri.get();
    }

    public void setUri(String uri) {
        this.uri.set(uri);
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMimeType() {
        return mimeType.get();
    }

    public void setMimeType(String mimeType) {
        this.mimeType.set(mimeType);
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

    public List<OperationStepSpec> getSteps() {
        return steps;
    }

    public List<OutputParameterSpec> getOutputParameters() {
        return outputParameters;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLocation() {
        return location.get();
    }

    public void setLocation(String location) {
        this.location.set(location);
    }

    /**
     * Returns {@code true} when this resource is served from a local file directory.
     */
    public boolean isStatic() {
        return location.get() != null;
    }

    /**
     * Returns {@code true} when the URI contains {@code {param}} placeholders (resource template).
     */
    public boolean isTemplate() {
        String value = uri.get();
        return value != null && value.contains("{") && value.contains("}");
    }
}
