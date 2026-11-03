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
package io.ikanos.durable.store.memory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.SignalInbox;

/** Signal inbox in process memory: one update per path, ever. */
final class MemorySignalInbox implements SignalInbox {

    private final Map<ExecutionId, Map<NodePath, byte[]>> pending = new HashMap<>();
    private final Map<ExecutionId, Set<NodePath>> delivered = new HashMap<>();

    @Override
    public synchronized boolean deliver(ExecutionId executionId, NodePath path, byte[] payload) {
        if (!delivered.computeIfAbsent(executionId, id -> new HashSet<>()).add(path)) {
            return false;
        }
        pending.computeIfAbsent(executionId, id -> new LinkedHashMap<>()).put(path,
                payload.clone());
        return true;
    }

    @Override
    public synchronized Map<NodePath, byte[]> pending(ExecutionId executionId) {
        Map<NodePath, byte[]> copy = new LinkedHashMap<>();
        pending.getOrDefault(executionId, Map.of()).forEach((p, b) -> copy.put(p, b.clone()));
        return copy;
    }

    @Override
    public synchronized void acknowledge(ExecutionId executionId, NodePath path) {
        Map<NodePath, byte[]> updates = pending.get(executionId);
        if (updates != null) {
            updates.remove(path);
        }
    }
}
