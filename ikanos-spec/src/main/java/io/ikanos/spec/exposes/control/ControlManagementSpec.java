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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Toggles individual control port management endpoint groups. Does not include OTel-dependent
 * endpoints (metrics, traces) — those are configured under observability.
 *
 * <h2>Thread safety</h2>
 * Boolean toggles use {@link AtomicBoolean}; reference fields use {@link AtomicReference}.
 * Lazy initialization of {@code logs} uses {@link AtomicReference#compareAndSet} to remain
 * thread-safe. This satisfies SonarQube rule {@code java:S3077}.
 */
public class ControlManagementSpec {

    private final AtomicBoolean health = new AtomicBoolean(true);
    private final AtomicBoolean info = new AtomicBoolean(false);
    private final AtomicBoolean reload = new AtomicBoolean(false);
    private final AtomicBoolean validate = new AtomicBoolean(false);

    private final AtomicReference<ControlLogsEndpointSpec> logs = new AtomicReference<>();
    private final AtomicReference<ScriptingManagementSpec> scripting = new AtomicReference<>();

    public boolean isHealth() {
        return health.get();
    }

    public void setHealth(boolean health) {
        this.health.set(health);
    }

    public boolean isInfo() {
        return info.get();
    }

    public void setInfo(boolean info) {
        this.info.set(info);
    }

    public boolean isReload() {
        return reload.get();
    }

    public void setReload(boolean reload) {
        this.reload.set(reload);
    }

    public boolean isValidate() {
        return validate.get();
    }

    public void setValidate(boolean validate) {
        this.validate.set(validate);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ControlLogsEndpointSpec getLogs() {
        ControlLogsEndpointSpec current = logs.get();
        if (current == null) {
            ControlLogsEndpointSpec candidate = new ControlLogsEndpointSpec();
            candidate.setLevelControl(false);
            if (logs.compareAndSet(null, candidate)) {
                return candidate;
            }
            return logs.get();
        }
        return current;
    }

    public void setLogs(ControlLogsEndpointSpec logs) {
        this.logs.set(logs);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ScriptingManagementSpec getScripting() {
        return scripting.get();
    }

    public void setScripting(ScriptingManagementSpec scripting) {
        this.scripting.set(scripting);
    }

    /**
     * Convenience accessor — returns true when log level control is enabled.
     */
    public boolean isLogging() {
        ControlLogsEndpointSpec current = logs.get();
        return current != null && current.isLevelControl();
    }
}
