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
package io.ikanos.spec.exposes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.InputParameterMapDeserializer;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;

/**
 * Base Exposed Adapter Specification Element.
 *
 * <h2>Thread safety</h2>
 * Each scalar field is held in an {@link AtomicReference} or {@link AtomicInteger}; the
 * {@code inputParameters} map is a synchronized {@link LinkedHashMap} wrapped in an
 * {@link AtomicReference}. This satisfies SonarQube rule {@code java:S3077}.
 */
@JsonDeserialize(using = ServerSpecDeserializer.class)
public abstract class ServerSpec {

    private final AtomicReference<String> type = new AtomicReference<>();
    private final AtomicReference<String> address = new AtomicReference<>();
    private final AtomicInteger port = new AtomicInteger();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = InputParameterMapDeserializer.class)
    private final AtomicReference<Map<String, InputParameterSpec>> inputParameters =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    private final AtomicReference<AuthenticationSpec> authentication = new AtomicReference<>();

    public ServerSpec() {
        this(null, "localhost", 0);
    }

    public ServerSpec(String type) {
        this(type, "localhost", 0);
    }

    public ServerSpec(String type, String address, int port) {
        this.type.set(type);
        this.address.set(address);
        this.port.set(port);
    }

    public String getType() {
        return type.get();
    }

    public void setType(String type) {
        this.type.set(type);
    }

    public String getAddress() {
        return address.get();
    }

    public void setAddress(String address) {
        this.address.set(address);
    }

    public int getPort() {
        return port.get();
    }

    public void setPort(int port) {
        this.port.set(port);
    }

    public List<InputParameterSpec> getInputParameters() {
        return List.copyOf(inputParameters.get().values());
    }

    public void setInputParameters(Map<String, InputParameterSpec> params) {
        Map<String, InputParameterSpec> snapshot = Collections.synchronizedMap(
                new LinkedHashMap<>(params != null ? params : Map.of()));
        inputParameters.set(snapshot);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AuthenticationSpec getAuthentication() {
        return authentication.get();
    }

    public void setAuthentication(AuthenticationSpec authentication) {
        this.authentication.set(authentication);
    }

}
