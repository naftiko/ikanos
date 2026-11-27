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

    /** The outcome of a call's result; {@code failure} for no result. */
    public static String of(ConsumedResult result) {
        return result == null ? FAILURE : of(result.status());
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
     * name that has a dash. A parameter the caller named {@code outcome} is kept, and the
     * outcomes are then not readable.
     */
    @SuppressWarnings("unchecked")
    public static void put(Map<String, Object> parameters, String step, String outcome) {
        if (step == null) {
            return;
        }
        Object root = parameters.get(ROOT);
        Map<String, Object> outcomes;
        if (root instanceof OutcomeMap map) {
            outcomes = map;
        } else if (root == null) {
            outcomes = new OutcomeMap();
            parameters.put(ROOT, outcomes);
        } else {
            return;
        }
        outcomes.put(step, outcome);
        String alias = step.replace('-', '_');
        if (!alias.equals(step)) {
            outcomes.putIfAbsent(alias, outcome);
        }
    }

    /**
     * A result that keeps only a call's status, for a step a success criterion accepted
     * although it did not answer {@code 2xx}: it has no body to pass through or journal.
     */
    public static ConsumedResult withoutBody(int status) {
        return new RestoredResult(status, null, null);
    }

    /** The map of outcomes, told apart from a caller's parameter of the same name. */
    static final class OutcomeMap extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
    }
}
