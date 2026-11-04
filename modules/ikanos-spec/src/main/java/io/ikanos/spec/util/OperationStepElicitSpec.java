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
package io.ikanos.spec.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@code elicit} step: asks the caller of the current invocation for input, through its
 * protocol, and continues with the answer as the step's output.
 *
 * <p>Over MCP, the question becomes an {@code elicitation/create} input request. Under the
 * {@code none} durability mode it uses a stateless multi round-trip request, which replays the
 * flow from the start with the answers collected so far; that is only safe when every step
 * before it is read-only.</p>
 */
public class OperationStepElicitSpec extends OperationStepSpec {

    private volatile String message;
    private volatile Map<String, Object> fields;

    public OperationStepElicitSpec() {
        this(null, null, null);
    }

    public OperationStepElicitSpec(String name, String message, Map<String, Object> fields) {
        super("elicit", name);
        this.message = message;
        this.fields = fields != null ? new LinkedHashMap<>(fields) : null;
    }

    /** The question shown to the caller; the step name when absent. */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * The fields asked for, by name: each value is a JSON Schema property (a primitive
     * {@code type}, optionally {@code description}, {@code enum}, {@code format}). Every field
     * is required.
     */
    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields != null ? new LinkedHashMap<>(fields) : null;
    }
}
