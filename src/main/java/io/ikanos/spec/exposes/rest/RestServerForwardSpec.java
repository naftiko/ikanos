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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Specification Element for forwarding trusted headers
 */
public class RestServerForwardSpec {

    private volatile String targetNamespace;

    private final List<String> trustedHeaders;

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

}
