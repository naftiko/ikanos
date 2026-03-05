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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.naftiko.spec.consumes.AuthenticationSpec;

/**
 * Web API Server Specification Element
 */
public class ApiServerSpec extends ServerSpec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile String namespace;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<ApiServerResourceSpec> resources;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile AuthenticationSpec authentication;

    public ApiServerSpec() {
        this(null, 0, null);
    }

    public ApiServerSpec(String address, int port, String namespace) {
        super("api", address, port);
        this.namespace = namespace;
        this.resources = new CopyOnWriteArrayList<>();
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public List<ApiServerResourceSpec> getResources() {
        return resources;
    }

    public AuthenticationSpec getAuthentication() {
        return authentication;
    }

    public void setAuthentication(AuthenticationSpec authentication) {
        this.authentication = authentication;
    }

}
