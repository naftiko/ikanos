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
package io.ikanos.durable.store;

import java.util.Map;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;

/** Signals and updates, each delivered exactly once to its execution. */
public interface SignalInbox {

    /**
     * Delivers an update addressed to {@code path}. Returns {@code false}, and changes nothing,
     * when an update for that path was already delivered.
     */
    boolean deliver(ExecutionId executionId, NodePath path, byte[] payload);

    /** Updates delivered but not yet acknowledged, by path. */
    Map<NodePath, byte[]> pending(ExecutionId executionId);

    /** Marks the update for {@code path} as journaled by the execution. */
    void acknowledge(ExecutionId executionId, NodePath path);
}
