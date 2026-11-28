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
package io.ikanos.spec.aggregates;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A call of a flow's {@code upload} block (experimental): a consumed operation, run once per file,
 * with its {@code with} values resolved against the call's arguments, where the file argument
 * carries its {@code uploadId}.
 */
public abstract class UploadStepSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String call;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile Map<String, Object> with;

    /** @return the consumed operation, as {@code namespace.operation} */
    public String getCall() {
        return call;
    }

    public void setCall(String call) {
        this.call = call;
    }

    /** @return the values passed to the operation, as Mustache templates, or {@code null} */
    public Map<String, Object> getWith() {
        return with;
    }

    public void setWith(Map<String, Object> with) {
        this.with = with != null ? Map.copyOf(with) : null;
    }
}
