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
package io.ikanos.engine.exposes.mcp;

import java.util.function.Supplier;

/**
 * The principal of the MCP request being dispatched, for state that must be bound to it: a
 * multi round-trip {@code requestState}, a task. It is the agent identity the dispatcher resolved
 * (verified first, then forwarded by a trusted peer), or {@value #ANONYMOUS} when there is none.
 */
public final class McpCallContext {

    /** The principal of a request without a resolved identity. */
    public static final String ANONYMOUS = "anonymous";

    private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();

    private McpCallContext() {
    }

    /** Runs {@code work} on behalf of {@code principal}. */
    static <T> T as(String principal, Supplier<T> work) {
        String previous = PRINCIPAL.get();
        PRINCIPAL.set(principal);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                PRINCIPAL.remove();
            } else {
                PRINCIPAL.set(previous);
            }
        }
    }

    /** The principal of the current request. */
    public static String principal() {
        String principal = PRINCIPAL.get();
        return principal != null ? principal : ANONYMOUS;
    }
}
