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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.ResourceSpec;

/**
 * HTTP Resource Specification Element.
 *
 * <h2>Thread safety</h2>
 * The {@code operations} map is a synchronized {@link LinkedHashMap} preserving YAML insertion
 * order (critical for call-reference resolution). This satisfies SonarQube rule {@code java:S3077}.
 */
public class HttpClientResourceSpec extends ResourceSpec {

    @JsonDeserialize(using = HttpClientOperationMapDeserializer.class)
    private final Map<String, HttpClientOperationSpec> operations =
            Collections.synchronizedMap(new LinkedHashMap<>());

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
    public Map<String, HttpClientOperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, HttpClientOperationSpec> operations) {
        if (operations == null) return;
        synchronized (this.operations) {
            this.operations.clear();
            operations.forEach((name, op) -> {
                op.setParentResource(this);
                this.operations.put(name, op);
            });
        }
    }
}
