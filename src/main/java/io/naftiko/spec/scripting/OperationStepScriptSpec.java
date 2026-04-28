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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.naftiko.spec.util.OperationStepSpec;

/**
 * Operation Step Script Specification Element
 * 
 * Represents a script step that executes JavaScript, Python, or Groovy code loaded from an external
 * file via the GraalVM Polyglot API or GroovyShell. The script result is stored in the step
 * execution context under the step's name.
 */
public class OperationStepScriptSpec extends OperationStepSpec {

    @JsonProperty("language")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String language;

    @JsonProperty("location")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String location;

    @JsonProperty("file")
    private volatile String file;

    @JsonProperty("dependencies")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> dependencies;

    @JsonProperty("with")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    public OperationStepScriptSpec() {
        this(null, null, null, null, null, null);
    }

    public OperationStepScriptSpec(String name, String language, String location, String file) {
        this(name, language, location, file, null, null);
    }

    public OperationStepScriptSpec(String name, String language, String location, String file,
            List<String> dependencies, Map<String, Object> with) {
        super("script", name);
        this.language = language;
        this.location = location;
        this.file = file;
        this.dependencies = new CopyOnWriteArrayList<>();
        if (dependencies != null) {
            this.dependencies.addAll(dependencies);
        }
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

}
