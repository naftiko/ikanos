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

/**
 * The agent behind an MCP request, as resolved by {@link AgentIdentityResolver}.
 *
 * @param id the agent identifier, e.g. {@code agent-42}.
 * @param source how it was obtained: {@link #SOURCE_FORWARDED} (asserted by a trusted gateway)
 *        or {@link #SOURCE_VERIFIED} (taken from a validated token).
 * @param actor who presented the token on the agent's behalf (the {@code sub} of an RFC 8693
 *        {@code act} claim, e.g. a gateway that exchanged the token), or {@code null}.
 */
public record AgentIdentity(String id, String source, String actor) {

    public static final String SOURCE_FORWARDED = "forwarded";
    public static final String SOURCE_VERIFIED = "verified";

    public AgentIdentity(String id, String source) {
        this(id, source, null);
    }
}
