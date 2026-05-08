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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base Authentication Specification Element
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY, // Include the type identifier as a property in the JSON
    property = "type" // The name of the JSON property holding the type identifier
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ApiKeyAuthenticationSpec.class, name = "apikey"),
    @JsonSubTypes.Type(value = BasicAuthenticationSpec.class, name = "basic"),
    @JsonSubTypes.Type(value = BearerAuthenticationSpec.class, name = "bearer"),
    @JsonSubTypes.Type(value = DigestAuthenticationSpec.class, name = "digest"),
    @JsonSubTypes.Type(value = OAuth2AuthenticationSpec.class, name = "oauth2")
})
public abstract class AuthenticationSpec {

    private volatile String type;

    public AuthenticationSpec() {
        this(null);
    }

    public AuthenticationSpec(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

}
