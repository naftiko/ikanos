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
package io.ikanos.engine.exposes.mcp.model;

/**
 * The {@code resultType} of an MCP {@code 2026-07-28} result: a complete result, a request for
 * input (multi round-trip requests), or a task handle (the tasks extension, SEP-2663).
 */
public enum McpResultType {

    /** The request is answered in full. */
    COMPLETE("complete"),

    /** The server needs input from the client before it can answer. */
    INPUT_REQUIRED("input_required"),

    /** The server answers with a task handle; the result comes through {@code tasks/get}. */
    TASK("task");

    /** The JSON field that carries the result type. */
    public static final String FIELD = "resultType";

    private final String wireValue;

    McpResultType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value on the wire. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses a wire value. A missing value means {@link #COMPLETE}, as in results from servers
     * that predate the field.
     *
     * @throws IllegalArgumentException for an unknown value
     */
    public static McpResultType fromWire(String value) {
        if (value == null || value.isEmpty()) {
            return COMPLETE;
        }
        for (McpResultType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MCP resultType: " + value);
    }
}
