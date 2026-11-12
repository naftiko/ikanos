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

import java.util.Map;
import java.util.concurrent.Callable;
import io.ikanos.durable.NodePath;

/**
 * The caller's answers to {@code elicit} steps, available to every run started by the current
 * request. A stateless multi round-trip request replays the flow from the start with them: an
 * {@code elicit} step whose path has an answer passes through with that answer as its output.
 */
public final class ExecutionInputs {

    /**
     * The answers available to a run: all of them, and those of the latest round of a journaled
     * call, with that round's number (0 before any), and the state a replay carries apart from
     * the answers, such as an upstream's {@code requestState}.
     */
    private record Inputs(Map<NodePath, Object> all, Map<NodePath, Object> latest, int round,
            Map<NodePath, Object> carried) {
    }

    private static final ThreadLocal<Inputs> CURRENT = new ThreadLocal<>();

    private ExecutionInputs() {
    }

    /** Runs {@code work} with {@code answers} available to the runs it starts. */
    public static <T> T with(Map<NodePath, Object> answers, Callable<T> work) throws Exception {
        return with(answers, Map.of(), 0, Map.of(), work);
    }

    /**
     * Runs {@code work} with {@code answers} available to the runs it starts, and the state a
     * replay carries ({@link InputRequiredException#carry}), kept apart from the answers so that
     * no answer key can overwrite it.
     */
    public static <T> T replaying(Map<NodePath, Object> answers, Map<NodePath, Object> carried,
            Callable<T> work) throws Exception {
        return with(answers, Map.of(), 0, carried, work);
    }

    /**
     * Runs {@code work} with {@code answers} available to the runs it starts, of which
     * {@code latest} arrived in round {@code round} of a journaled call.
     */
    public static <T> T with(Map<NodePath, Object> answers, Map<NodePath, Object> latest,
            int round, Callable<T> work) throws Exception {
        return with(answers, latest, round, Map.of(), work);
    }

    private static <T> T with(Map<NodePath, Object> answers, Map<NodePath, Object> latest,
            int round, Map<NodePath, Object> carried, Callable<T> work) throws Exception {
        Inputs previous = CURRENT.get();
        CURRENT.set(new Inputs(Map.copyOf(answers), Map.copyOf(latest), round,
                Map.copyOf(carried)));
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * The caller's answers available on this thread, by key: those a multi round-trip request or
     * a round of a journaled call carried. Empty when there are none.
     */
    public static Map<NodePath, Object> answers() {
        return current();
    }

    /**
     * The answers that arrived in the latest round of a journaled call ({@link #latestRound}),
     * by key: an upstream asking again forwards only these, never an answer it already had.
     */
    public static Map<NodePath, Object> latestAnswers() {
        Inputs inputs = CURRENT.get();
        return inputs != null ? inputs.latest() : Map.of();
    }

    /** The round of a journaled call whose answers {@link #latestAnswers} holds; 0 before any. */
    public static int latestRound() {
        Inputs inputs = CURRENT.get();
        return inputs != null ? inputs.round() : 0;
    }

    /**
     * The state a replay carries, by key, such as an upstream's {@code requestState}: never an
     * answer. Empty when there is none.
     */
    public static Map<NodePath, Object> carried() {
        Inputs inputs = CURRENT.get();
        return inputs != null ? inputs.carried() : Map.of();
    }

    static Map<NodePath, Object> current() {
        Inputs inputs = CURRENT.get();
        return inputs != null ? inputs.all() : Map.of();
    }
}
