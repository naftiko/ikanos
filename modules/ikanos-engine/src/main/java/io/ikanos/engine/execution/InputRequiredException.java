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
     * @param message the elicit step's message, rendered against the run's parameters when the
     *        question was asked; when {@code null}, the step's name is shown, never its message
     *        as written, which may be an unrendered template
     * @param url what a URL mode elicit step sends its caller to; {@code null} for a form
     *        question or an upstream's
     */
    public record Question(String key, OperationStepElicitSpec step, JsonNode request,
            String message, UrlRequest url) {

        public Question(String key, OperationStepElicitSpec step) {
            this(key, step, null, null, null);
        }

        public Question(String key, OperationStepElicitSpec step, JsonNode request) {
            this(key, step, request, null, null);
        }

        public Question(String key, OperationStepElicitSpec step, JsonNode request,
                String message) {
            this(key, step, request, message, null);
        }

        /**
         * The elicitation mode the question is asked in, {@code form} or {@code url}: an
         * upstream's request names it (form when it names none), an elicit step declares it.
         */
        public String mode() {
            if (request != null) {
                return request.path("params").path("mode")
                        .asString(OperationStepElicitSpec.FORM);
            }
            return step != null && step.isUrlMode() ? OperationStepElicitSpec.URL
                    : OperationStepElicitSpec.FORM;
        }
    }

    /**
     * What a URL mode question sends its caller to.
     *
     * @param url the page, rendered when the question was asked, which may carry the
     *        question's id ({@code {{elicitation.id}}}); the request sends no other id, since the
     *        {@code 2026-07-28} {@code ElicitRequestURLParams} defines none
     */
    public record UrlRequest(String url) {
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

    /**
     * The keys of earlier answers a replay no longer needs and should not carry: those of the
     * visits of a URL mode step asked again before its latest answered one.
     */
    public List<String> droppedAnswers() {
        return carry == null ? List.of() : Elicitation.droppedAnswers(carry);
    }

    /** The elicitation modes the questions are asked in, in the order they first appear. */
    public java.util.Set<String> modes() {
        java.util.Set<String> modes = new java.util.LinkedHashSet<>();
        questions.forEach(question -> modes.add(question.mode()));
        return modes;
    }
}
