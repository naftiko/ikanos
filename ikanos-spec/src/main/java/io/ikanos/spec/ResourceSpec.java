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
package io.ikanos.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Resource Specification Element
 *
 * <h2>Thread safety</h2>
 * {@code inputParameters} is stored as a synchronized {@link LinkedHashMap} wrapped in an
 * {@link AtomicReference} to allow atomic replacement during deserialization while preserving
 * declaration order. This satisfies SonarQube rule {@code java:S3077}.
 */
public class ResourceSpec {

    private volatile String path;

    private volatile String name;

    private volatile String label;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = InputParameterMapDeserializer.class)
    private final AtomicReference<Map<String, InputParameterSpec>> inputParameters =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    public ResourceSpec() {
        this(null, null, null, null);
    }

    public ResourceSpec(String path, String name, String label, String description) {
        this.path = path;
        this.name = name;
        this.label = label;
        this.description = description;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns input parameters in declaration order. Consumers (engine) are unchanged
     * because they iterate the returned collection.
     */
    public List<InputParameterSpec> getInputParameters() {
        return List.copyOf(inputParameters.get().values());
    }

    /**
     * Called by Jackson during deserialization (via {@link InputParameterMapDeserializer}).
     */
    public void setInputParameters(Map<String, InputParameterSpec> params) {
        Map<String, InputParameterSpec> snapshot = Collections.synchronizedMap(new LinkedHashMap<>(params != null ? params : Map.of()));
        inputParameters.set(snapshot);
    }
}
