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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;

import io.naftiko.spec.ResourceSpec;

/**
 * API Resource Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code operations} list and {@code forward} reference are held in
 * {@link AtomicReference}s so that they can be replaced atomically. The {@code operations}
 * list is stored as a {@link CopyOnWriteArrayList} snapshot to keep element-level
 * thread-safety. This satisfies SonarQube rule {@code java:S3077}.
 */
public class RestServerResourceSpec extends ResourceSpec {

    private final AtomicReference<List<RestServerOperationSpec>> operations =
            new AtomicReference<>(new CopyOnWriteArrayList<>());
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
    public List<RestServerOperationSpec> getOperations() {
        return operations.get();
    }

    /**
     * Sets operations and establishes parent resource reference for each operation.
     * This ensures that each {@link RestServerOperationSpec} knows its parent
     * {@link RestServerResourceSpec}.
     *
     * <p>{@code @JsonSetter} ensures this method is called by Jackson during deserialization.</p>
     */
    @JsonSetter
    public void setOperations(List<RestServerOperationSpec> operations) {
        if (operations == null) {
            this.operations.set(new CopyOnWriteArrayList<>());
            return;
        }
        CopyOnWriteArrayList<RestServerOperationSpec> snapshot = new CopyOnWriteArrayList<>(operations);
        for (RestServerOperationSpec operation : snapshot) {
            operation.setParentResource(this);
        }
        this.operations.set(snapshot);
    }

    public RestServerForwardSpec getForward() {
        return forward.get();
    }

    public void setForward(RestServerForwardSpec forward) {
        this.forward.set(forward);
    }

}
