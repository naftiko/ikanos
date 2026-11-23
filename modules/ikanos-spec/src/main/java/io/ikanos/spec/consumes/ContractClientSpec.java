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
package io.ikanos.spec.consumes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.ikanos.spec.InputParameterMapDeserializer;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.tunnel.TunnelConfigSpec;

/**
 * Base of the consumed adapters described by a local contract file: {@code grpc} (a protobuf
 * descriptor set), {@code graphql} (an SDL schema) and {@code smithy} (a Smithy model).
 *
 * <p>They share the adapter-level fields of the consumed-adapter shape: {@code description},
 * {@code authentication}, {@code tunnel}, and header {@code inputParameters} ({@code in: header}
 * with a {@code value}) sent on every call, as for {@code type: http}.</p>
 */
public abstract class ContractClientSpec extends ClientSpec implements TunneledClientSpec {

    private final AtomicReference<String> description = new AtomicReference<>();
    private final AtomicReference<AuthenticationSpec> authentication = new AtomicReference<>();
    private final AtomicReference<TunnelConfigSpec> tunnel = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = InputParameterMapDeserializer.class)
    private final AtomicReference<Map<String, InputParameterSpec>> inputParameters =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    protected ContractClientSpec(String type) {
        super(type, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description.get();
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AuthenticationSpec getAuthentication() {
        return authentication.get();
    }

    public void setAuthentication(AuthenticationSpec authentication) {
        this.authentication.set(authentication);
    }

    @Override
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public TunnelConfigSpec getTunnel() {
        return tunnel.get();
    }

    public void setTunnel(TunnelConfigSpec tunnel) {
        this.tunnel.set(tunnel);
    }

    /** @return the header parameters sent on every call */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<InputParameterSpec> getInputParameters() {
        return List.copyOf(inputParameters.get().values());
    }

    public void setInputParameters(Map<String, InputParameterSpec> params) {
        inputParameters.set(Collections.synchronizedMap(
                new LinkedHashMap<>(params != null ? params : Map.of())));
    }

    /** @return the declared operations, keyed by call name */
    public abstract Map<String, ? extends ContractOperationSpec> operations();
}
