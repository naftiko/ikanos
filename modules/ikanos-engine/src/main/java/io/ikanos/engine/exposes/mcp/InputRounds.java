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

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionFailedException;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionView;
import io.ikanos.durable.Failure;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.engine.execution.DurableScope;
import io.ikanos.engine.execution.ExecutionInputs;
import io.ikanos.engine.execution.InputRequiredException;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A journaled MCP call that may ask its caller for input, in rounds: a task, or an inline
 * {@code tools/call} or {@code resources/read} answered by multi round-trip requests.
 *
 * <p>Round 1 calls the tool. A round whose flow stops at {@code elicit} steps ends with the
 * questions as its outcome, and the execution waits for an update holding the answers
 * ({@code answers@n}). The next round calls the tool again with every answer so far: the steps
 * journaled in earlier rounds are restored, the answered {@code elicit} steps pass, and the flow
 * goes on. A round that returns a result completes the execution.</p>
 */
public final class InputRounds {

    /** Path of the call; round {@code n} visits it {@code n} times. */
    public static final NodePath CALL = NodePath.of("tool-call");

    /** Path of the update that answers the questions of a round. */
    public static final NodePath ANSWERS = NodePath.of("answers");

    /** Field of a call outcome that stopped at questions instead of returning a result. */
    static final String ASKED = "ikanos:inputRequests";

    /** Field of such an outcome: when the round asked, in epoch milliseconds. */
    static final String ASKED_AT = "ikanos:askedAt";

    /** Failure class of a call rejected for invalid parameters: a JSON-RPC error. */
    public static final String INVALID_PARAMS = "invalid-params";

    private static final ObjectMapper MAPPER = IkanosMappers.json();

    private InputRounds() {
    }

    /**
     * What round {@code n} runs with: every answer so far, and those of round {@code n - 1},
     * the latest, with that round's number (0 for the first round).
     */
    record RoundInput(Map<NodePath, Object> answers, Map<NodePath, Object> latest,
            int latestRound) {
    }

    /** The Interpreter of a call in rounds. */
    public static Interpreter interpreter() {
        return view -> {
            Map<NodePath, Object> answers = new LinkedHashMap<>();
            Map<NodePath, Object> latest = Map.of();
            for (int round = 1;; round++) {
                NodePath call = CALL.visit(round);
                var outcome = view.outcome(call);
                if (outcome.isEmpty()) {
                    return List.of(new Command.ScheduleAction(call,
                            new RoundInput(Map.copyOf(answers), latest, round - 1), null));
                }
                if (outcome.get() instanceof ActionOutcome.Failed failed) {
                    return List.of(new Command.Fail(failed.failure()));
                }
                Object value = ((ActionOutcome.Succeeded) outcome.get()).value();
                if (!(value instanceof JsonNode node && node.has(ASKED))) {
                    return List.of(new Command.Complete(value));
                }
                NodePath answered = ANSWERS.visit(round);
                var update = view.update(answered);
                if (update.isEmpty()) {
                    return List.of(new Command.AwaitUpdate(answered, node.get(ASKED)));
                }
                Map<NodePath, Object> received = new LinkedHashMap<>();
                ((JsonNode) update.get()).properties()
                        .forEach(e -> received.put(new NodePath(e.getKey()), e.getValue()));
                answers.putAll(received);
                latest = Map.copyOf(received);
            }
        };
    }

    /**
     * The executor of a call in rounds: {@code work} runs with the answers so far, its steps
     * journaled under {@code executionId}. Questions end the round, recorded with when it asked
     * ({@link #askedAt}); an {@link IllegalArgumentException} fails it with
     * {@link #INVALID_PARAMS}.
     */
    public static ActionExecutor executor(JournaledRunner runner, ExecutionId executionId,
            BooleanSupplier stopping, Clock clock, Callable<JsonNode> work) {
        return action -> {
            RoundInput input = (RoundInput) action.action();
            // A cancelled call stops at its next step, as a shutdown does: cancel is cooperative,
            // and a step in flight is not interrupted reliably.
            BooleanSupplier stop = () -> stopping.getAsBoolean()
                    || runner.status(executionId).orElse(ExecutionStatus.RUNNING)
                            == ExecutionStatus.CANCELLED;
            try {
                return ExecutionInputs.with(input.answers(), input.latest(), input.latestRound(),
                        () -> DurableScope.with(runner, executionId, stop, work));
            } catch (IllegalArgumentException e) {
                throw new ActionFailedException(new Failure(INVALID_PARAMS, e.getMessage()), e);
            } catch (InputRequiredException e) {
                ObjectNode asked = MAPPER.createObjectNode();
                asked.set(ASKED, InputRequests.of(e));
                asked.put(ASKED_AT, clock.millis());
                return asked;
            }
        };
    }

    /** The last round with a recorded call outcome; 1 before the first one ends. */
    public static int lastRound(ExecutionView view) {
        int round = 1;
        while (view.outcome(CALL.visit(round + 1)).isPresent()) {
            round++;
        }
        return round;
    }

    /**
     * When the last round of the call asked, or empty when it did not stop at questions or was
     * recorded without the time.
     */
    public static java.util.Optional<Instant> askedAt(ExecutionView view) {
        return view.outcome(CALL.visit(lastRound(view)))
                .filter(ActionOutcome.Succeeded.class::isInstance)
                .map(outcome -> ((ActionOutcome.Succeeded) outcome).value())
                .filter(value -> value instanceof JsonNode node && node.has(ASKED_AT))
                .map(value -> Instant.ofEpochMilli(((JsonNode) value).get(ASKED_AT).asLong()));
    }

    /** The questions a round stopped at, or {@code null} when it did not stop at questions. */
    public static ObjectNode questions(ExecutionView view, int round) {
        return view.outcome(CALL.visit(round))
                .filter(ActionOutcome.Succeeded.class::isInstance)
                .map(outcome -> ((ActionOutcome.Succeeded) outcome).value())
                .filter(value -> value instanceof JsonNode node && node.has(ASKED))
                .map(value -> (ObjectNode) ((JsonNode) value).get(ASKED))
                .orElse(null);
    }

    /** The responses to the given questions, by key; others are left out. */
    public static ObjectNode answersTo(ObjectNode questions, JsonNode responses) {
        ObjectNode answers = MAPPER.createObjectNode();
        if (questions != null && responses != null && responses.isObject()) {
            for (String key : questions.propertyNames()) {
                if (responses.has(key)) {
                    answers.set(key, responses.get(key));
                }
            }
        }
        return answers;
    }
}
