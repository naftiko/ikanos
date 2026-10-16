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
 * Limits on aggregate flow execution, configured on the control adapter.
 *
 * <p>An aggregate flow may call another flow ({@code call: namespace.flow-name}). Cycles are
 * rejected at load time; {@code maxCallDepth} bounds how many flows may be active on one call
 * chain, counting the first one.</p>
 */
public class FlowManagementSpec {

    /** Default for {@link #getMaxCallDepth()}. */
    public static final int DEFAULT_MAX_CALL_DEPTH = 8;

    @JsonProperty("maxCallDepth")
    private volatile int maxCallDepth = DEFAULT_MAX_CALL_DEPTH;

    /** @return the maximum number of flows active on one call chain, counting the first */
    public int getMaxCallDepth() {
        return maxCallDepth;
    }

    public void setMaxCallDepth(int maxCallDepth) {
        this.maxCallDepth = maxCallDepth;
    }
}
