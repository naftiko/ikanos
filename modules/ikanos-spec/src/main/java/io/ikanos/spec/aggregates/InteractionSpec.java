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

/**
 * What a caller may observe while a flow runs: {@code semantics.interaction}.
 *
 * <p>{@code mode: task} lets an MCP client that declares the tasks extension receive a task handle
 * instead of waiting for the result. It needs a durability mode other than {@code none} on the
 * control port.</p>
 */
public class InteractionSpec {

    /** The default mode: the result comes back in the response to the call. */
    public static final String MODE_INLINE = "inline";

    /** The caller may receive a task handle and poll for the result. */
    public static final String MODE_TASK = "task";

    private String mode;
    private Long expectedDurationMs;

    public InteractionSpec() {}

    public InteractionSpec(String mode, Long expectedDurationMs) {
        this.mode = mode;
        this.expectedDurationMs = expectedDurationMs;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Long getExpectedDurationMs() {
        return expectedDurationMs;
    }

    public void setExpectedDurationMs(Long expectedDurationMs) {
        this.expectedDurationMs = expectedDurationMs;
    }

    /** Whether the flow may answer with a task handle. */
    public boolean isTask() {
        return MODE_TASK.equals(mode);
    }
}
