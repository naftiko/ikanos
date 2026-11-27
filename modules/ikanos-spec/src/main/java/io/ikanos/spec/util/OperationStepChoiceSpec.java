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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A {@code choice} step: runs {@code then} when {@code if} holds, {@code else} otherwise.
 *
 * <p>{@code if:} is read like a guard: a bare name or a CEL expression. Each path is one step or
 * a {@code sequence}; three paths nest a {@code choice} in {@code else}. Exactly one path runs,
 * and the step's output is that path's output. Without {@code else}, a condition that does not
 * hold runs nothing, and the step has no output.</p>
 */
public class OperationStepChoiceSpec extends OperationStepSpec implements NestedSteps {

    /** The name of {@code then} when it declares none. */
    public static final String DEFAULT_THEN = "then";

    /** The name of {@code else} when it declares none. */
    public static final String DEFAULT_ELSE = "else";

    @JsonProperty("if")
    private volatile String condition;

    @JsonProperty("then")
    private volatile OperationStepSpec then;

    @JsonProperty("else")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private volatile OperationStepSpec otherwise;

    public OperationStepChoiceSpec() {
        this(null);
    }

    public OperationStepChoiceSpec(String name) {
        super("choice", name);
    }

    public String getIf() {
        return condition;
    }

    public void setIf(String condition) {
        this.condition = condition;
    }

    public OperationStepSpec getThen() {
        return then;
    }

    /** Sets {@code then}, naming it {@code then} when it declares no name of its own. */
    public void setThen(OperationStepSpec then) {
        if (then != null && then.getName() == null) {
            then.setName(DEFAULT_THEN);
        }
        this.then = then;
    }

    public OperationStepSpec getElse() {
        return otherwise;
    }

    /** Sets {@code else}, naming it {@code else} when it declares no name of its own. */
    public void setElse(OperationStepSpec otherwise) {
        if (otherwise != null && otherwise.getName() == null) {
            otherwise.setName(DEFAULT_ELSE);
        }
        this.otherwise = otherwise;
    }

    /** {@code then}, and {@code else} when there is one. */
    @Override
    public Map<String, OperationStepSpec> nestedSteps() {
        Map<String, OperationStepSpec> nested = new LinkedHashMap<>();
        if (then != null) {
            nested.put(then.getName(), then);
        }
        if (otherwise != null) {
            // Both paths are listed, even when the author gave them the same name.
            String key = nested.containsKey(otherwise.getName())
                    ? otherwise.getName() + "#else" : otherwise.getName();
            nested.put(key, otherwise);
        }
        return nested;
    }
}
