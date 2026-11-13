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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Identity Header Specification Element: one entry of {@code forward.identityHeaders} (#998).
 *
 * <p>The forwarded request gets this header with the value of a claim of the caller's validated
 * OAuth 2.1 token, set by the engine. Written as the claim name ({@code X-User-Id: sub}), or as an
 * object to choose what happens when the token does not carry the claim
 * ({@code X-User-Id: { claim: sub, ifMissing: omit }}).</p>
 */
public class IdentityHeaderSpec {

    /** Fail the call when the claim is missing (the default). */
    public static final String IF_MISSING_FAIL = "fail";

    /** Send the request without the header when the claim is missing. */
    public static final String IF_MISSING_OMIT = "omit";

    private volatile String claim;
    private volatile String ifMissing;

    public IdentityHeaderSpec() {}

    public IdentityHeaderSpec(String claim, String ifMissing) {
        this.claim = claim;
        this.ifMissing = ifMissing;
    }

    /** Reads the short form, {@code X-User-Id: sub}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static IdentityHeaderSpec ofClaim(String claim) {
        return new IdentityHeaderSpec(claim, null);
    }

    /** @return the claim name, or a dotted path into nested claims such as {@code realm_access.roles} */
    public String getClaim() {
        return claim;
    }

    public void setClaim(String claim) {
        this.claim = claim;
    }

    /** @return {@link #IF_MISSING_FAIL}, {@link #IF_MISSING_OMIT}, or {@code null} for the default */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getIfMissing() {
        return ifMissing;
    }

    public void setIfMissing(String ifMissing) {
        this.ifMissing = ifMissing;
    }

    /** @return whether the header is left out, rather than the call failed, when the claim is missing */
    public boolean omitsWhenMissing() {
        return IF_MISSING_OMIT.equals(ifMissing);
    }
}
