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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where a capability keeps its executions: the control port's {@code durability} block, declared
 * once per capability. Flows carry no durability settings.
 */
public class DurabilitySpec {

    /** No journal: every execution runs inline, as before. The default. */
    public static final String MODE_NONE = "none";

    /** Journal in process memory: tasks and pauses work while the process lives. */
    public static final String MODE_MEMORY = "memory";

    /** Journal in an embedded H2 file, surviving a restart. */
    public static final String MODE_LOCAL = "local";

    /** Journal in a shared store, for several replicas. */
    public static final String MODE_DISTRIBUTED = "distributed";

    private String mode;
    private DurabilityStoreSpec store;
    private String retention;
    private String sealingKey;

    public DurabilitySpec() {}

    public DurabilitySpec(String mode) {
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public DurabilityStoreSpec getStore() {
        return store;
    }

    public void setStore(DurabilityStoreSpec store) {
        this.store = store;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getRetention() {
        return retention;
    }

    public void setRetention(String retention) {
        this.retention = retention;
    }

    /**
     * The key sealing the state handed to clients, such as an MCP {@code requestState}: a
     * base64-encoded AES key of 128, 192 or 256 bits, usually a {@code {{...}}} reference to a
     * {@code binds} variable. Absent, each process draws a random key.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSealingKey() {
        return sealingKey;
    }

    public void setSealingKey(String sealingKey) {
        this.sealingKey = sealingKey;
    }

    /** The declared mode, {@link #MODE_NONE} when absent. */
    public String effectiveMode() {
        return mode == null || mode.isBlank() ? MODE_NONE : mode;
    }
}
