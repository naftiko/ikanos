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
package io.ikanos.engine.exposes.mcp.search;

/**
 * How an MCP endpoint advertises its tools to one request.
 */
public enum ToolSearchMode {

    /** {@code tools/list} returns every tool. */
    LISTED("listed"),

    /** {@code tools/list} returns the two meta-tools and the pinned tools. */
    DEFERRED("deferred");

    private final String value;

    ToolSearchMode(String value) {
        this.value = value;
    }

    /** @return the value written in the spec and on the URL */
    public String value() {
        return value;
    }

    /**
     * Parses a spec or URL value, case-sensitively.
     *
     * @param value {@code listed} or {@code deferred}
     * @return the mode
     * @throws IllegalArgumentException for any other value
     */
    public static ToolSearchMode parse(String value) {
        for (ToolSearchMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported toolSearch value '" + value + "'; expected listed or deferred");
    }
}
