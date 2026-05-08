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
package io.ikanos.spec.consumes.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;

import io.ikanos.spec.ResourceSpec;

/**
 * HTTP Resource Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code operations} list is held in an {@link AtomicReference} wrapping a
 * {@link CopyOnWriteArrayList} for both reference-level and element-level thread-safety.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
public class HttpClientResourceSpec extends ResourceSpec {

    private final AtomicReference<List<HttpClientOperationSpec>> operations =
            new AtomicReference<>(new CopyOnWriteArrayList<>());

    public HttpClientResourceSpec() {
        this(null, null, null, null);
    }

    public HttpClientResourceSpec(String path, String name, String label) {
        this(path, name, label, null);
    }

    public HttpClientResourceSpec(String path, String name, String label, String description) {
        super(path, name, label, description);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<HttpClientOperationSpec> getOperations() {
        return operations.get();
    }

    /**
     * Sets operations and establishes parent resource reference for each operation.
     * This ensures that each {@link HttpClientOperationSpec} knows its parent
     * {@link HttpClientResourceSpec}.
     *
     * <p>{@code @JsonSetter} ensures this method is called by Jackson during deserialization.</p>
     */
    @JsonSetter
    public void setOperations(List<HttpClientOperationSpec> operations) {
        if (operations == null) {
            this.operations.set(new CopyOnWriteArrayList<>());
            return;
        }
        CopyOnWriteArrayList<HttpClientOperationSpec> snapshot = new CopyOnWriteArrayList<>(operations);
        for (HttpClientOperationSpec operation : snapshot) {
            operation.setParentResource(this);
        }
        this.operations.set(snapshot);
    }
}
