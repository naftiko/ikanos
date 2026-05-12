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
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.ResourceSpec;

/**
 * API Resource Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code operations} map and {@code forward} reference are held in
 * {@link AtomicReference}s. Operations are stored as a synchronized {@link LinkedHashMap}
 * to preserve YAML insertion order. This satisfies SonarQube rule {@code java:S3077}.
 */
public class RestServerResourceSpec extends ResourceSpec {

    @JsonDeserialize(using = RestServerOperationMapDeserializer.class)
    private final Map<String, RestServerOperationSpec> operations =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private final AtomicReference<RestServerForwardSpec> forward = new AtomicReference<>();

    public RestServerResourceSpec() {
        this(null, null, null, null, null);
    }

    public RestServerResourceSpec(String path) {
        this(path, null, null, null, null);
    }

    public RestServerResourceSpec(String path, String name, String label, String description, RestServerForwardSpec forward) {
        super(path, name, label, description);
        this.forward.set(forward);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, RestServerOperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, RestServerOperationSpec> operations) {
        if (operations == null) return;
        synchronized (this.operations) {
            this.operations.clear();
            operations.forEach((name, op) -> {
                op.setParentResource(this);
                this.operations.put(name, op);
            });
        }
    }

    public RestServerForwardSpec getForward() { return forward.get(); }
    public void setForward(RestServerForwardSpec forward) { this.forward.set(forward); }
}
