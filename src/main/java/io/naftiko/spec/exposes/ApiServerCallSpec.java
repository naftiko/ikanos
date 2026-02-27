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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API Call Specification Element
 * 
 * Represents a call to another operation with associated input parameters.
 * The "with" field contains key-value pairs that provide values to the input parameters
 * of the target operation.
 */
public class ApiServerCallSpec {

    private volatile String operation;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    public ApiServerCallSpec() {
        this(null, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ApiServerCallSpec(String operation) {
        this(operation, null);
    }

    public ApiServerCallSpec(String operation, Map<String, Object> with) {
        this.operation = operation;
        this.with = with != null ? new ConcurrentHashMap<>(with) : new ConcurrentHashMap<>();
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : new ConcurrentHashMap<>();
    }

    /**
     * Gets a parameter value from the "with" map by key.
     * 
     * @param key the parameter key
     * @return the parameter value, or null if not present
     */
    public Object getParameter(String key) {
        return with != null ? with.get(key) : null;
    }

    /**
     * Sets a parameter value in the "with" map.
     * 
     * @param key the parameter key
     * @param value the parameter value
     */
    public void setParameter(String key, Object value) {
        if (with == null) {
            with = new ConcurrentHashMap<>();
        }
        with.put(key, value);
    }

}
