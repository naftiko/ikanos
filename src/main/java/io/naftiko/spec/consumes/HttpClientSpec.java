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
package io.naftiko.spec.consumes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.naftiko.spec.InputParameterSpec;

/**
 * Specification Element of consumed HTTP adapter endpoints
 */
@JsonDeserialize(using = JsonDeserializer.None.class)
public class HttpClientSpec extends ClientSpec {

    private volatile String baseUri;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<InputParameterSpec> inputParameters;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile AuthenticationSpec authentication;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<HttpClientResourceSpec> resources;

    public HttpClientSpec(String namespace, String baseUri, AuthenticationSpec authentication) {
        super("http", namespace);
        // Validate: baseUri must not have a trailing slash per Naftiko specification
        if (baseUri != null && baseUri.endsWith("/")) {
            throw new IllegalArgumentException(
                    "baseUri must not end with a trailing slash. Provided: '" + baseUri + "'");
        }
        this.baseUri = baseUri;
        this.inputParameters = new CopyOnWriteArrayList<>();
        this.authentication = authentication;
        this.resources = new CopyOnWriteArrayList<>();
    }

    public HttpClientSpec(String baseUri) {
        this(null, baseUri, null);
    }

    public HttpClientSpec() {
        this(null);
    }

    public String getBaseUri() {
        return baseUri;
    }

    public void setBaseUri(String baseUri) {
        // Validate: baseUri must not have a trailing slash per Naftiko specification
        if (baseUri != null && baseUri.endsWith("/")) {
            throw new IllegalArgumentException(
                    "baseUri must not end with a trailing slash. Provided: '" + baseUri + "'");
        }
        this.baseUri = baseUri;
    }

    public List<InputParameterSpec> getInputParameters() {
        return inputParameters;
    }

    public AuthenticationSpec getAuthentication() {
        return authentication;
    }

    public void setAuthentication(AuthenticationSpec authentication) {
        this.authentication = authentication;
    }

    public List<HttpClientResourceSpec> getResources() {
        return resources;
    }

}
