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
package io.ikanos.engine.exposes;

/**
 * Resolves a declared cache scope ({@code public}, {@code private} or {@code auto}) to the scope a
 * result is sent with, and says why.
 *
 * <p>{@code auto} becomes {@code private} when the result can depend on who is calling, and
 * {@code public} otherwise. Shared by the exposed adapters so they agree on the rule.</p>
 */
public final class CacheScopes {

    public static final String PUBLIC = "public";
    public static final String PRIVATE = "private";
    public static final String AUTO = "auto";

    private CacheScopes() {
        // Utility class — no instances.
    }

    /**
     * The scope a result is sent with, and the reason it was chosen.
     *
     * @param scope {@code public} or {@code private}
     * @param reason why, for debug output and linting
     */
    public record Decision(String scope, String reason) {
    }

    /**
     * Resolve {@code declared}.
     *
     * @param declared {@code public}, {@code private} or {@code auto}
     * @param callerDependence why the result can depend on the caller, or {@code null} when it
     *        cannot
     * @return the resolved scope and its reason
     */
    public static Decision resolve(String declared, String callerDependence) {
        if (!AUTO.equals(declared)) {
            return new Decision(declared, "declared " + declared);
        }
        return callerDependence != null
                ? new Decision(PRIVATE, "auto: " + callerDependence)
                : new Decision(PUBLIC, "auto: the result does not depend on the caller");
    }
}
