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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.util.CallerClaimMissingException;
import io.ikanos.engine.util.CallerRoot;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.OperationStepElicitSpec;

/**
 * Unit tests for {@link Elicitation#message}: the text an {@code elicit} step shows its caller,
 * rendered from the run's parameters when the question is asked.
 */
class ElicitationMessageTest {

    private static OperationStepElicitSpec step(String message) {
        return new OperationStepElicitSpec("approve", message,
                Map.of("confirm", Map.of("type", "string")));
    }

    @Test
    void messageShouldRenderInputsAndStepOutputs() {
        Map<String, Object> parameters = Map.of("postId", "42",
                "post", Map.of("title", "Hello World"));

        String message = Elicitation.message(
                step("Permanently delete '{{post.title}}' (#{{postId}})?"), parameters);

        assertEquals("Permanently delete 'Hello World' (#42)?", message);
    }

    @Test
    void messageShouldBeTheStepNameWhenTheStepDeclaresNone() {
        assertEquals("approve", Elicitation.message(step(null), Map.of("postId", "42")));
    }

    @Test
    void messageShouldBeKeptAsWrittenWhenItHasNoPlaceholder() {
        assertEquals("Delete this post?", Elicitation.message(step("Delete this post?"), null));
    }

    @Test
    void messageShouldNotEscapeHtmlInTheRenderedValues() {
        String message = Elicitation.message(step("Delete '{{post.title}}'?"),
                Map.of("post", Map.of("title", "Tom & Jerry <draft>")));

        assertEquals("Delete 'Tom & Jerry <draft>'?", message);
    }

    @Test
    void messageShouldFailNamingTheVariableWhenNothingProvidesIt() {
        OperationStepElicitSpec step = step("Delete '{{post.title}}' (#{{postId}})?");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Elicitation.message(step, Map.of("postId", "42")));

        assertTrue(e.getMessage().contains("post.title"), e.getMessage());
        assertTrue(e.getMessage().contains("approve"), e.getMessage());
    }

    @Test
    void parametersShouldIncludeAStepOutputKeptOnlyInTheStepContext() {
        // A lookup step stores its output in the step context, not in the run's parameters.
        StepRun run = new StepRun(Map.of("postId", "42"));
        run.stepContext().storeStepOutput("author",
                IkanosMappers.json().createObjectNode().put("name", "Grace"));

        String message = Elicitation.message(step("Delete #{{postId}} by {{author.name}}?"),
                Elicitation.parameters(run));

        assertEquals("Delete #42 by Grace?", message);
    }

    @Test
    void parametersShouldKeepTheRunParameterWhenAStepOutputHasTheSameName() {
        StepRun run = new StepRun(Map.of("post", Map.of("title", "From the run")));
        run.stepContext().storeStepOutput("post",
                IkanosMappers.json().createObjectNode().put("title", "From the context"));

        assertEquals("From the run", Elicitation.message(step("{{post.title}}"),
                Elicitation.parameters(run)));
    }

    @Test
    void messageShouldRenderAClaimOfTheCaller() {
        Map<String, Object> parameters = Map.of(Resolver.CALLER_ROOT,
                new CallerRoot(Map.of("claims", Map.of("sub", "alice"))));

        assertEquals("Approve as alice?",
                Elicitation.message(step("Approve as {{auth.claims.sub}}?"), parameters));
    }

    @Test
    void messageShouldFailAsAMissingClaimWhenTheTokenLacksIt() {
        Map<String, Object> parameters = Map.of(Resolver.CALLER_ROOT,
                new CallerRoot(Map.of("claims", Map.of("sub", "alice"))));
        OperationStepElicitSpec step = step("Approve as {{auth.claims.name}}?");

        assertThrows(CallerClaimMissingException.class,
                () -> Elicitation.message(step, parameters));
    }

    @Test
    void messageShouldLeaveOutAnOptionalSectionWhoseValueIsMissing() {
        String message = Elicitation.message(
                step("Delete post #{{postId}}{{#post.title}} '{{post.title}}'{{/post.title}}?"),
                Map.of("postId", "42"));

        assertEquals("Delete post #42?", message);
    }
}
