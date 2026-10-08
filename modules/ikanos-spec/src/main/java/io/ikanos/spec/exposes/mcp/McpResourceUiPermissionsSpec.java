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
package io.ikanos.spec.exposes.mcp;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Browser permissions requested by an MCP Apps view. Authored as booleans ({@code true} =
 * requested) and emitted as empty objects under {@code _meta.ui.permissions}, which is the
 * presence-flag shape of SEP-1865.
 *
 * <h2>Thread safety</h2>
 * Each flag is held in an {@link AtomicReference}.
 */
public class McpResourceUiPermissionsSpec {

    private final AtomicReference<Boolean> camera = new AtomicReference<>();
    private final AtomicReference<Boolean> microphone = new AtomicReference<>();
    private final AtomicReference<Boolean> geolocation = new AtomicReference<>();
    private final AtomicReference<Boolean> clipboardWrite = new AtomicReference<>();

    public McpResourceUiPermissionsSpec() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getCamera() { return camera.get(); }
    public void setCamera(Boolean value) { camera.set(value); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getMicrophone() { return microphone.get(); }
    public void setMicrophone(Boolean value) { microphone.set(value); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getGeolocation() { return geolocation.get(); }
    public void setGeolocation(Boolean value) { geolocation.set(value); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getClipboardWrite() { return clipboardWrite.get(); }
    public void setClipboardWrite(Boolean value) { clipboardWrite.set(value); }
}
