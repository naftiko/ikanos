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
package io.ikanos.spec.exposes.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Specification Element for forwarding trusted headers
 */
public class RestServerForwardSpec {

    private volatile String targetNamespace;

    private final List<String> trustedHeaders;

    private final Map<String, IdentityHeaderSpec> identityHeaders =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public RestServerForwardSpec() {
        this(null);
    }

    public RestServerForwardSpec(String targetNamespace) {
        this.targetNamespace = targetNamespace;
        this.trustedHeaders = new CopyOnWriteArrayList<>();
    }

    public String getTargetNamespace() {
        return targetNamespace;
    }

    public void setTargetNamespace(String targetNamespace) {
        this.targetNamespace = targetNamespace;
    }

    public List<String> getTrustedHeaders() {
        return trustedHeaders;
    }

    /**
     * @return the headers the engine sets from the caller's validated claims, keyed by header name,
     *         in declaration order (#998). Never {@code null}.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, IdentityHeaderSpec> getIdentityHeaders() {
        return identityHeaders;
    }

    public void setIdentityHeaders(Map<String, IdentityHeaderSpec> identityHeaders) {
        synchronized (this.identityHeaders) {
            this.identityHeaders.clear();
            if (identityHeaders != null) {
                this.identityHeaders.putAll(identityHeaders);
            }
        }
    }

}
