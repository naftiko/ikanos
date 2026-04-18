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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Toggles individual control port endpoint groups. Health and metrics default to enabled; all
 * development endpoints default to disabled.
 */
public class ControlEndpointsSpec {

    private volatile boolean health = true;
    private volatile boolean metrics = true;
    private volatile boolean info = false;
    private volatile boolean reload = false;
    private volatile boolean validate = false;
    private volatile boolean logging = false;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ControlTracesEndpointSpec traces;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile ControlLogsEndpointSpec logs;

    public boolean isHealth() {
        return health;
    }

    public void setHealth(boolean health) {
        this.health = health;
    }

    public boolean isMetrics() {
        return metrics;
    }

    public void setMetrics(boolean metrics) {
        this.metrics = metrics;
    }

    public boolean isInfo() {
        return info;
    }

    public void setInfo(boolean info) {
        this.info = info;
    }

    public boolean isReload() {
        return reload;
    }

    public void setReload(boolean reload) {
        this.reload = reload;
    }

    public boolean isValidate() {
        return validate;
    }

    public void setValidate(boolean validate) {
        this.validate = validate;
    }

    public boolean isLogging() {
        return logging;
    }

    public void setLogging(boolean logging) {
        this.logging = logging;
    }

    public ControlTracesEndpointSpec getTraces() {
        if (traces == null) {
            traces = new ControlTracesEndpointSpec();
        }
        return traces;
    }

    public void setTraces(ControlTracesEndpointSpec traces) {
        this.traces = traces;
    }

    public ControlLogsEndpointSpec getLogs() {
        if (logs == null) {
            logs = new ControlLogsEndpointSpec();
        }
        return logs;
    }

    public void setLogs(ControlLogsEndpointSpec logs) {
        this.logs = logs;
    }
}
