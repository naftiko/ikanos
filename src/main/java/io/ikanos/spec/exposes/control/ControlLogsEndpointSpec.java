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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Configuration for /logs endpoints on the control port. Supports a boolean shorthand ({@code logs:
 * true}) that enables all log endpoints with defaults, or an object form for advanced configuration.
 */
@JsonDeserialize(using = ControlLogsEndpointSpecDeserializer.class)
public class ControlLogsEndpointSpec {

    @JsonProperty("level-control")
    private volatile boolean levelControl = true;

    private volatile boolean stream = false;

    @JsonProperty("max-subscribers")
    private volatile int maxSubscribers = 5;

    public boolean isLevelControl() {
        return levelControl;
    }

    public void setLevelControl(boolean levelControl) {
        this.levelControl = levelControl;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public int getMaxSubscribers() {
        return maxSubscribers;
    }

    public void setMaxSubscribers(int maxSubscribers) {
        this.maxSubscribers = maxSubscribers;
    }

    /**
     * Creates a spec with all log endpoints enabled and default settings. Used when {@code logs:
     * true} is specified.
     */
    static ControlLogsEndpointSpec allEnabled() {
        ControlLogsEndpointSpec spec = new ControlLogsEndpointSpec();
        spec.setLevelControl(true);
        spec.setStream(true);
        return spec;
    }
}
