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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Authorization Specification Element ({@code authorization}).
 *
 * <p>What the caller's validated OAuth 2.1 token must carry to reach an exposed adapter, resource,
 * operation or tool: every listed scope, and for each listed claim one of its allowed values. The
 * nearest level wins: a tool's or an operation's requirement replaces the broader ones.</p>
 */
public class AuthorizationSpec {

    private volatile List<String> scopes = List.of();
    private volatile Map<String, List<String>> claims = Map.of();

    public AuthorizationSpec() {}

    /**
     * Returns the requirement that applies: the narrowest one declared. Pass the levels from the
     * broadest to the narrowest (adapter, resource, operation; or adapter, tool). Shared by the
     * engine and the OpenAPI export so both read the same inheritance.
     *
     * @return the last non-null requirement, or {@code null} when no level declares one
     */
    public static AuthorizationSpec nearest(AuthorizationSpec... broadestToNarrowest) {
        AuthorizationSpec found = null;
        for (AuthorizationSpec requirement : broadestToNarrowest) {
            if (requirement != null) {
                found = requirement;
            }
        }
        return found;
    }

    public AuthorizationSpec(List<String> scopes, Map<String, List<String>> claims) {
        setScopes(scopes);
        setClaims(claims);
    }

    /** @return the scopes the token must all carry, never {@code null}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes != null ? List.copyOf(scopes) : List.of();
    }

    /** @return the allowed values of each required claim, by claim name, never {@code null}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, List<String>> getClaims() {
        return claims;
    }

    public void setClaims(Map<String, List<String>> claims) {
        if (claims == null) {
            this.claims = Map.of();
            return;
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        claims.forEach((name, values) -> copy.put(name,
                values != null ? List.copyOf(values) : List.of()));
        this.claims = Collections.unmodifiableMap(copy);
    }

}
