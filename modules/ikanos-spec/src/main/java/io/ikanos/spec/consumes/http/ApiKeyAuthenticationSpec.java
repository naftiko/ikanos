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

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * API Key Authentication Specification Element
 */
public class ApiKeyAuthenticationSpec extends AuthenticationSpec {

    private volatile String key;
    private volatile String placement;
    private volatile String value;

    public ApiKeyAuthenticationSpec() {
        this(null, null, null);
    }   

    public ApiKeyAuthenticationSpec(String key, String placement, String value) {
        super("apikey");
        this.key = key;
        this.placement = placement;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getPlacement() {
        return placement;
    }

    public void setPlacement(String placement) {
        this.placement = placement;
    }

    /**
     * Where the key goes, the same rule for an exposed and a consumed adapter: in the query only
     * when {@code placement} says {@code query} (in any case), in a header otherwise, including
     * when it is omitted, as the schema says (#840).
     *
     * @return {@code true} when the key goes in the query string
     */
    @JsonIgnore
    public boolean isQueryPlacement() {
        return "query".equalsIgnoreCase(placement);
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

}
