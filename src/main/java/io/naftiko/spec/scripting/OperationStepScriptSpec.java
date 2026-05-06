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
package io.naftiko.spec.scripting;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.naftiko.spec.util.OperationStepSpec;

/**
 * Operation Step Script Specification Element.
 *
 * <p>Represents a script step that executes JavaScript, Python, or Groovy code loaded from an
 * external file via the GraalVM Polyglot API or GroovyShell. The script result is stored in the
 * step execution context under the step's name.</p>
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference}; the {@code with} parameter map is
 * stored as an immutable snapshot. The {@code dependencies} list is a {@link CopyOnWriteArrayList}.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
public class OperationStepScriptSpec extends OperationStepSpec {

    private final AtomicReference<String> language = new AtomicReference<>();
    private final AtomicReference<String> location = new AtomicReference<>();
    private final AtomicReference<String> file = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();

    @JsonProperty("dependencies")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> dependencies;

    public OperationStepScriptSpec() {
        this(null, null, null, null, null, null);
    }

    public OperationStepScriptSpec(String name, String language, String location, String file) {
        this(name, language, location, file, null, null);
    }

    public OperationStepScriptSpec(String name, String language, String location, String file,
            List<String> dependencies, Map<String, Object> with) {
        super("script", name);
        this.language.set(language);
        this.location.set(location);
        this.file.set(file);
        this.dependencies = new CopyOnWriteArrayList<>();
        if (dependencies != null) {
            this.dependencies.addAll(dependencies);
        }
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

    @JsonProperty("language")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLanguage() {
        return language.get();
    }

    @JsonProperty("language")
    public void setLanguage(String language) {
        this.language.set(language);
    }

    @JsonProperty("location")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getLocation() {
        return location.get();
    }

    @JsonProperty("location")
    public void setLocation(String location) {
        this.location.set(location);
    }

    @JsonProperty("file")
    public String getFile() {
        return file.get();
    }

    @JsonProperty("file")
    public void setFile(String file) {
        this.file.set(file);
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    @JsonProperty("with")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getWith() {
        return with.get();
    }

    @JsonProperty("with")
    public void setWith(Map<String, Object> with) {
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

}