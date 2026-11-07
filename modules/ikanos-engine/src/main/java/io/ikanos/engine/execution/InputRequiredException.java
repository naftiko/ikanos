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
package io.ikanos.engine.execution;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.util.OperationStepElicitSpec;

/**
 * Thrown when a run stops at {@code elicit} steps that have no answer yet. The exposed adapter
 * projects it (an MCP {@code InputRequiredResult}); adapters that cannot ask their caller treat
 * it as a failure.
 *
 * <p>It is not an error: every layer between the runner and the adapter rethrows it
 * unchanged.</p>
 */
public class InputRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * One question for the caller.
     *
     * @param key the question's key, unique within the request: an elicit step's node path, or
     *        a consumed tool's question under the calling step's path
     * @param step the elicit step, or {@code null} for a question an upstream asked
     * @param request the upstream's {@code elicitation/create} request, passed through
     *        unchanged, or {@code null} for an elicit step's own question
     */
    public record Question(String key, OperationStepElicitSpec step, JsonNode request) {

        public Question(String key, OperationStepElicitSpec step) {
            this(key, step, null);
        }
    }

    private final transient List<Question> questions;
    private final transient Map<String, JsonNode> carry;

    public InputRequiredException(List<Question> questions) {
        this(questions, Map.of());
    }

    /**
     * @param carry state a replay needs, by key, kept with the answers in the caller's sealed
     *        state, such as an upstream's own {@code requestState}
     */
    public InputRequiredException(List<Question> questions, Map<String, JsonNode> carry) {
        super("Input required: " + questions.stream().map(Question::key).toList(), null, false,
                false);
        this.questions = List.copyOf(questions);
        this.carry = Map.copyOf(carry);
    }

    /** State to keep with the answers for a replay, by key. */
    public Map<String, JsonNode> carry() {
        return carry;
    }

    /** The questions to ask, in the order the steps reached them. */
    public List<Question> questions() {
        return questions;
    }
}
