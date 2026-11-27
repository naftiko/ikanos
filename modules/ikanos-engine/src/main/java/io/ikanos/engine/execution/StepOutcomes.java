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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.ikanos.engine.consumes.ConsumedResult;

/**
 * The protocol-neutral outcome of a call step, which guards and success criteria read as
 * {@code outcome.<step>}.
 *
 * <p>One flow mixes HTTP, MCP, SQL and other calls, which share no status codes, so a step's
 * outcome is a word every adapter maps to from the status its result reports:</p>
 * <ul>
 * <li>{@code success}: a {@code 2xx} answer;</li>
 * <li>{@code not-found}: the target does not exist ({@code 404}, {@code 410});</li>
 * <li>{@code throttled}: the upstream refused for now ({@code 429});</li>
 * <li>{@code unreachable}: no answer was received (a connection or timeout failure);</li>
 * <li>{@code failure}: any other answer.</li>
 * </ul>
 *
 * <p>The outcome follows from the status alone, which a durable run journals, so a replayed run
 * sees the same outcomes.</p>
 */
public final class StepOutcomes {

    /** The template root under which the outcomes are read: {@code outcome.<step>}. */
    public static final String ROOT = "outcome";

    public static final String SUCCESS = "success";
    public static final String NOT_FOUND = "not-found";
    public static final String THROTTLED = "throttled";
    public static final String UNREACHABLE = "unreachable";
    public static final String FAILURE = "failure";

    /** Every outcome, in the order the spec lists them. */
    public static final List<String> VOCABULARY =
            List.of(SUCCESS, NOT_FOUND, THROTTLED, UNREACHABLE, FAILURE);

    private StepOutcomes() {
    }

    /**
     * The outcome of a call's result: the one its adapter reports, if any, else the one its
     * status maps to; {@code failure} for no result.
     */
    public static String of(ConsumedResult result) {
        if (result == null) {
            return FAILURE;
        }
        String reported = result.stepOutcome();
        return reported != null && VOCABULARY.contains(reported) ? reported
                : of(result.status());
    }

    /**
     * A failure that knows the outcome of the call it ended, such as a consumed MCP call whose
     * tool reported an error: a call step with success criteria records it and reads them
     * instead of failing at once.
     */
    public interface Reported {
        /** @return the outcome, one of {@link #VOCABULARY} */
        String stepOutcome();
    }

    /**
     * The outcome a failure reports, found in its cause chain, or {@code null} when none does.
     */
    public static String reportedBy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Reported reported && reported.stepOutcome() != null
                    && VOCABULARY.contains(reported.stepOutcome())) {
                return reported.stepOutcome();
            }
        }
        return null;
    }

    /** The outcome of a call that answered with {@code status}. */
    public static String of(int status) {
        if (ConsumedResult.noResponseReceived(status)) {
            return UNREACHABLE;
        }
        if (status >= 200 && status < 300) {
            return SUCCESS;
        }
        if (status == 404 || status == 410) {
            return NOT_FOUND;
        }
        if (status == 429) {
            return THROTTLED;
        }
        return FAILURE;
    }

    /**
     * Records a step's outcome in the run's parameters, under {@link #ROOT}, with the alias of a
     * name that has a dash. The map is replaced, never changed in place: parameters are copied
     * shallowly into the flows a step calls, so a called flow must not write into its caller's
     * outcomes. The name {@code outcome} is reserved ({@code StepGuardValidator} refuses an
     * input, {@code with} key or step of that name), so any other value under it is replaced.
     */
    public static void put(Map<String, Object> parameters, String step, String outcome) {
        if (step == null || outcome == null) {
            return;
        }
        OutcomeMap outcomes = parameters.get(ROOT) instanceof OutcomeMap map
                ? new OutcomeMap(map) : new OutcomeMap();
        outcomes.put(step, outcome);
        String alias = step.replace('-', '_');
        if (!alias.equals(step)) {
            outcomes.putIfAbsent(alias, outcome);
        }
        parameters.put(ROOT, outcomes);
    }

    /** The outcome recorded for {@code step} in the parameters, or {@code null}. */
    public static String get(Map<String, Object> parameters, String step) {
        return parameters.get(ROOT) instanceof OutcomeMap map
                && map.get(step) instanceof String outcome ? outcome : null;
    }

    /** Whether {@code value} is the engine's own map of outcomes. */
    static boolean isOutcomes(Object value) {
        return value instanceof OutcomeMap;
    }

    /**
     * Removes the outcomes a run inherits from its caller's parameters: a flow, or a track, reads
     * the outcomes of its own steps only, so a replay, which does not run a finished call again,
     * gives it the same ones.
     */
    static void forget(Map<String, Object> parameters) {
        if (parameters.get(ROOT) instanceof OutcomeMap) {
            parameters.remove(ROOT);
        }
    }

    /**
     * The result a run keeps for a step a success criterion accepted although it did not answer
     * {@code 2xx}: a success with no content ({@code 204}), since the step succeeded and has no
     * output. A flow or an operation that ends on it answers as a success, not with the
     * upstream's error. Its outcome is journaled on its own ({@code RestoredResult.OUTCOME}), as
     * the status no longer tells it.
     */
    public static ConsumedResult accepted() {
        return new RestoredResult(204, null, null);
    }

    /** The map of outcomes, told apart from a caller's parameter of the same name. */
    static final class OutcomeMap extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;

        OutcomeMap() {
        }

        OutcomeMap(Map<String, Object> outcomes) {
            super(outcomes);
        }
    }
}
