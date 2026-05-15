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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.consumes.ClientSpec;

/**
 * Specification Element of consumed HTTP adapter endpoints.
 *
 * <h2>Thread safety</h2>
 * The {@code baseUri} and {@code authentication} fields are held in {@link AtomicReference}s.
 * The {@code inputParameters} list is a {@link java.util.concurrent.CopyOnWriteArrayList}.
 * The {@code resources} map is a synchronized {@link LinkedHashMap} preserving YAML order.
 * This satisfies SonarQube rule {@code java:S3077}.
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class HttpClientSpec extends ClientSpec {

    private final AtomicReference<String> baseUri = new AtomicReference<>();
    private final AtomicReference<AuthenticationSpec> authentication = new AtomicReference<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = io.ikanos.spec.InputParameterMapDeserializer.class)
    private final AtomicReference<Map<String, InputParameterSpec>> inputParameters =
            new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<>()));

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonDeserialize(using = HttpClientResourceMapDeserializer.class)
    private final Map<String, HttpClientResourceSpec> resources =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public HttpClientSpec(String namespace, String baseUri, AuthenticationSpec authentication) {
        super("http", namespace);
        validateBaseUri(baseUri);
        this.baseUri.set(baseUri);
        this.authentication.set(authentication);
    }

    public HttpClientSpec(String baseUri) {
        this(null, baseUri, null);
    }

    public HttpClientSpec() {
        this(null);
    }

    public String getBaseUri() { return baseUri.get(); }

    public void setBaseUri(String baseUri) {
        validateBaseUri(baseUri);
        this.baseUri.set(baseUri);
    }

    private static void validateBaseUri(String baseUri) {
        if (baseUri != null && baseUri.endsWith("/")) {
            throw new IllegalArgumentException(
                    "baseUri must not end with a trailing slash. Provided: '" + baseUri + "'");
        }
    }

    public java.util.List<InputParameterSpec> getInputParameters() {
        return java.util.List.copyOf(inputParameters.get().values());
    }

    public void setInputParameters(Map<String, InputParameterSpec> params) {
        Map<String, InputParameterSpec> snapshot = Collections.synchronizedMap(
                new LinkedHashMap<>(params != null ? params : Map.of()));
        inputParameters.set(snapshot);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AuthenticationSpec getAuthentication() { return authentication.get(); }
    public void setAuthentication(AuthenticationSpec authentication) { this.authentication.set(authentication); }

    public Map<String, HttpClientResourceSpec> getResources() { return resources; }

    public void setResources(Map<String, HttpClientResourceSpec> resources) {
        if (resources == null) return;
        synchronized (this.resources) {
            this.resources.clear();
            this.resources.putAll(resources);
        }
    }
}
