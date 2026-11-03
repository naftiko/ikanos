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

    private static final ThreadLocal<Map<NodePath, Object>> CURRENT = new ThreadLocal<>();

    private ExecutionInputs() {
    }

    /** Runs {@code work} with {@code answers} available to the runs it starts. */
    public static <T> T with(Map<NodePath, Object> answers, Callable<T> work) throws Exception {
        Map<NodePath, Object> previous = CURRENT.get();
        CURRENT.set(Map.copyOf(answers));
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

    /** The answers of the current request; empty outside {@link #with}. */
    static Map<NodePath, Object> current() {
        Map<NodePath, Object> answers = CURRENT.get();
        return answers != null ? answers : Map.of();
    }
}
