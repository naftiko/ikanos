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
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Operation Step Call Specification Element
 * 
 * Represents a call to a consumed operation within an orchestration step.
 * Includes the operation reference and optional parameter injection via WithInjector.
 */
public class OperationStepCallSpec extends OperationStepSpec {

    @JsonProperty("call")
    private volatile String call;

    @JsonProperty("with")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    public OperationStepCallSpec() {
        this(null, null, null, null);
    }

    public OperationStepCallSpec(String name, String call) {
        this(name, call, null);
    }

    public OperationStepCallSpec(String name, String call, Map<String, Object> with) {
        this("call", name, call, with);
    }

    public OperationStepCallSpec(String type, String name, String call, Map<String, Object> with) {
        super(type, name);
        this.call = call;
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

    public String getCall() {
        return call;
    }

    public void setCall(String call) {
        this.call = call;
    }

    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? new ConcurrentHashMap<>(with) : null;
    }

}
