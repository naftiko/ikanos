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
package io.ikanos.spec.consumes.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.http.tunnel.TunnelConfigSpec;

/**
 * Specification Element of consumed HTTP adapter endpoints.
 *
 * <h2>Thread safety</h2>
 * The {@code baseUri} and {@code authentication} fields are held in {@link AtomicReference}s.
 * The {@code inputParameters} and {@code resources} lists are {@link CopyOnWriteArrayList}s.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class HttpClientSpec extends ClientSpec {

    private final AtomicReference<String> baseUri = new AtomicReference<>();
    private final AtomicReference<AuthenticationSpec> authentication = new AtomicReference<>();
    private final AtomicReference<TunnelConfigSpec> tunnel = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<HttpClientResourceSpec> resources;

    public HttpClientSpec(String namespace, String baseUri, AuthenticationSpec authentication) {
        super("http", namespace);
        validateBaseUri(baseUri);
        this.baseUri.set(baseUri);
        this.inputParameters = new CopyOnWriteArrayList<>();
        this.authentication.set(authentication);
        this.resources = new CopyOnWriteArrayList<>();
    }

    public HttpClientSpec(String baseUri) {
        this(null, baseUri, null);
    }

    public HttpClientSpec() {
        this(null);
    }

    public String getBaseUri() {
        return baseUri.get();
    }

    public void setBaseUri(String baseUri) {
        validateBaseUri(baseUri);
        this.baseUri.set(baseUri);
    }

    /**
     * Validates that {@code baseUri} does not have a trailing slash, per Ikanos specification.
     */
    private static void validateBaseUri(String baseUri) {
        if (baseUri != null && baseUri.endsWith("/")) {
            throw new IllegalArgumentException(
                    "baseUri must not end with a trailing slash. Provided: '" + baseUri + "'");
        }
    }

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AuthenticationSpec getAuthentication() {
        return authentication.get();
    }

    public void setAuthentication(AuthenticationSpec authentication) {
        this.authentication.set(authentication);
    }

    /**
     * Optional reverse-tunnel transport. When set, requests are dialed through the
     * configured overlay (e.g. an OpenZiti service) instead of the public network.
     * Returns {@code null} when no {@code tunnel:} block is declared in the YAML.
     *
     * <p>Engine runtime support is delivered in a later phase of the Reverse Tunnel
     * blueprint; the spec layer parses and validates the block today.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public TunnelConfigSpec getTunnel() {
        return tunnel.get();
    }

    public void setTunnel(TunnelConfigSpec tunnel) {
        this.tunnel.set(tunnel);
    }

    public List<HttpClientResourceSpec> getResources() {
        return resources;
    }

}
