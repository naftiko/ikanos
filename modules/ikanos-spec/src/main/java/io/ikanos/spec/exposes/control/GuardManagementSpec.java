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
package io.ikanos.spec.exposes.control;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Limits on step guards ({@code onlyIf:}), configured on the control adapter.
 *
 * <p>A guard is a CEL expression evaluated on the request thread, over values that upstreams
 * return: a macro such as {@code exists} nested in another grows with the square of their
 * lists. {@code evaluationLimit} bounds how many sub-expressions one evaluation of a guard may
 * evaluate, counting each iteration of a macro and the items of each list or map it reads; a
 * guard that exceeds it fails its step.</p>
 */
public class GuardManagementSpec {

    /** Default for {@link #getEvaluationLimit()}. */
    public static final long DEFAULT_EVALUATION_LIMIT = 100_000L;

    @JsonProperty("evaluationLimit")
    private volatile long evaluationLimit = DEFAULT_EVALUATION_LIMIT;

    /** @return the most sub-expressions one evaluation of a guard may evaluate */
    public long getEvaluationLimit() {
        return evaluationLimit;
    }

    public void setEvaluationLimit(long evaluationLimit) {
        this.evaluationLimit = evaluationLimit;
    }
}
