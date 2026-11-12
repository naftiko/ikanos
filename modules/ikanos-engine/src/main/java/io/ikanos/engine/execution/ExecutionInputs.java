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
import java.util.Map;
import java.util.concurrent.Callable;
import io.ikanos.durable.NodePath;

/**
 * The caller's answers to {@code elicit} steps, available to every run started by the current
 * request. A stateless multi round-trip request replays the flow from the start with them: an
 * {@code elicit} step whose path has an answer passes through with that answer as its output.
 *
 * <p>Answers are addressed by the full path of the calling steps: an {@code elicit} step
 * {@code steps/confirm} of a flow called by step {@code steps/crew} is asked, and answered, as
 * {@code steps/crew/steps/confirm}. Two called flows whose elicit steps share a name are
 * therefore asked apart, and an answer to one never answers the other. Each run sees its own
 * answers under its local paths ({@link #current()}) and reports its questions under the full
 * path ({@link #qualify(NodePath)}).</p>
 */
public final class ExecutionInputs {

    /**
     * The answers of the request, and the path of the calling steps of the current run
     * ({@code null} at the top level). Captured when a run starts and set again in each of its
     * actions, which may run on a thread of their own.
     */
    record Snapshot(Map<NodePath, Object> answers, NodePath caller) {
    }

    private static final Snapshot EMPTY = new Snapshot(Map.of(), null);
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private ExecutionInputs() {
    }

    /** Runs {@code work} with {@code answers} available to the runs it starts. */
    public static <T> T with(Map<NodePath, Object> answers, Callable<T> work) throws Exception {
        return run(new Snapshot(Map.copyOf(answers), null), work);
    }

    /** The answers and calling path of the current thread, to carry into the actions of a run. */
    static Snapshot capture() {
        Snapshot snapshot = CURRENT.get();
        return snapshot != null ? snapshot : EMPTY;
    }

    /**
     * Runs {@code work}, the action at {@code step} of a run started under {@code snapshot}: the
     * runs it starts are called by that step.
     */
    static <T> T within(Snapshot snapshot, NodePath step, Callable<T> work) throws Exception {
        NodePath caller = snapshot.caller() == null ? step
                : new NodePath(snapshot.caller().value() + "/" + step.value());
        return run(new Snapshot(snapshot.answers(), caller), work);
    }

    /** The answers addressed to the current run, keyed by its local paths; empty outside one. */
    static Map<NodePath, Object> current() {
        Snapshot snapshot = capture();
        if (snapshot.caller() == null) {
            return snapshot.answers();
        }
        String prefix = snapshot.caller().value() + "/";
        Map<NodePath, Object> local = new LinkedHashMap<>();
        snapshot.answers().forEach((path, answer) -> {
            if (path.value().startsWith(prefix)) {
                local.put(new NodePath(path.value().substring(prefix.length())), answer);
            }
        });
        return local;
    }

    /** The key under which the caller is asked for the {@code elicit} step at {@code path}. */
    static NodePath qualify(NodePath path) {
        NodePath caller = capture().caller();
        return caller == null ? path : new NodePath(caller.value() + "/" + path.value());
    }

    private static <T> T run(Snapshot snapshot, Callable<T> work) throws Exception {
        Snapshot previous = CURRENT.get();
        CURRENT.set(snapshot);
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
}
