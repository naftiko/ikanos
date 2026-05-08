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
package io.ikanos.spec.exposes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Server Call Specification Element.
 *
 * <p>Represents a call to another operation with associated input parameters.
 * The {@code with} field contains key-value pairs that provide values to the input parameters
 * of the target operation.</p>
 *
 * <h2>Thread safety</h2>
 * Each field is held in an {@link AtomicReference}; the {@code with} parameter map is stored
 * as an immutable snapshot. Mutations via {@link #setParameter(String, Object)} use
 * {@link AtomicReference#updateAndGet} to perform a lock-free copy-on-write replacement.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
public class ServerCallSpec {

    private final AtomicReference<String> operation = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> with = new AtomicReference<>();
    private final AtomicReference<String> description = new AtomicReference<>();

    public ServerCallSpec() {
        this(null, null, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ServerCallSpec(String operation) {
        this(operation, null, null);
    }

    public ServerCallSpec(String operation, Map<String, Object> with) {
        this(operation, with, null);
    }

    public ServerCallSpec(String operation, Map<String, Object> with, String description) {
        this.operation.set(operation);
        this.with.set(with != null ? Map.copyOf(with) : null);
        this.description.set(description);
    }

    public String getOperation() {
        return operation.get();
    }

    public void setOperation(String operation) {
        this.operation.set(operation);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> getWith() {
        return with.get();
    }

    public void setWith(Map<String, Object> with) {
        this.with.set(with != null ? Map.copyOf(with) : null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    /**
     * Gets a parameter value from the {@code with} map by key.
     *
     * @param key the parameter key
     * @return the parameter value, or {@code null} if not present
     */
    public Object getParameter(String key) {
        Map<String, Object> snapshot = with.get();
        return snapshot == null ? null : snapshot.get(key);
    }

    /**
     * Sets a parameter value in the {@code with} map.
     * Performs a lock-free copy-on-write replacement of the immutable snapshot.
     *
     * @param key the parameter key
     * @param value the parameter value
     */
    public void setParameter(String key, Object value) {
        with.updateAndGet(current -> {
            Map<String, Object> next = current == null ? new HashMap<>() : new HashMap<>(current);
            next.put(key, value);
            return Map.copyOf(next);
        });
    }

}
