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
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API Operation Step Specification Element
 * 
 * Represents a step in an API operation workflow.
 * A step contains a call specification that defines which operation to invoke
 * and what parameters to pass to it.
 */
public class RestServerStepSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ServerCallSpec call;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String description;

    public RestServerStepSpec() {
        this(null, null, null);
    }

    public RestServerStepSpec(ServerCallSpec call) {
        this(call, null, null);
    }

    public RestServerStepSpec(ServerCallSpec call, Map<String, Object> with) {
        this(call, with, null);
    }

    public RestServerStepSpec(ServerCallSpec call, Map<String, Object> with, String description) {
        this.call = call;
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
        this.description = description;
    }

    public ServerCallSpec getCall() {
        return call;
    }

    public void setCall(ServerCallSpec call) {
        this.call = call;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}

