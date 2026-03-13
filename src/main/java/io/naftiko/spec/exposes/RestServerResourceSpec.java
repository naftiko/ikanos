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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.naftiko.spec.ResourceSpec;

/**
 * API Resource Specification Element
 */
public class RestServerResourceSpec extends ResourceSpec {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private volatile List<RestServerOperationSpec> operations;

    private volatile RestServerForwardSpec forward;

    public RestServerResourceSpec() {
        this(null, null, null, null, null);
    }
    
    public RestServerResourceSpec(String path) {
        this(path, null, null, null, null);
    }

    public RestServerResourceSpec(String path, String name, String label, String description, RestServerForwardSpec forward) {
        super(path, name, label, description);
        this.operations = new CopyOnWriteArrayList<>();
        this.forward = forward;
    }

    public List<RestServerOperationSpec> getOperations() {
        return operations;
    }

    /**
     * Sets operations and establishes parent resource reference for each operation.
     * This ensures that each ApiOperationSpec knows its parent ResourceSpec.
     * 
     * @JsonSetter ensures this method is called by Jackson during deserialization
     */
    @JsonSetter
    public void setOperations(List<RestServerOperationSpec> operations) {
        this.operations = operations;
        if (operations != null) {
            for (RestServerOperationSpec operation : operations) {
                operation.setParentResource(this);
            }
        }
    }

    public RestServerForwardSpec getForward() {
        return forward;
    }

    public void setForward(RestServerForwardSpec forward) {
        this.forward = forward;
    }

}
