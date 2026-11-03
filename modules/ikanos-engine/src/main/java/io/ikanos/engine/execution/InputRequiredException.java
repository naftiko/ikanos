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
     * @param key the elicit step's node path, unique within the request
     * @param step the elicit step
     */
    public record Question(String key, OperationStepElicitSpec step) {
    }

    private final transient List<Question> questions;

    public InputRequiredException(List<Question> questions) {
        super("Input required: " + questions.stream().map(Question::key).toList(), null, false,
                false);
        this.questions = List.copyOf(questions);
    }

    /** The questions to ask, in the order the steps reached them. */
    public List<Question> questions() {
        return questions;
    }
}
