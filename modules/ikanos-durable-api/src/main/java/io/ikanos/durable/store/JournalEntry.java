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

import java.util.Arrays;
import java.util.Objects;
import io.ikanos.durable.NodePath;

/**
 * One record of an execution's journal.
 *
 * @param sequence position in the journal, starting at 1, without gaps
 * @param kind what happened
 * @param path the node it happened to, or {@code null} for the execution itself
 * @param payload the encoded value, failure, decision or update, or an empty array
 */
public record JournalEntry(long sequence, Kind kind, NodePath path, byte[] payload) {

    /** What a journal entry records. */
    public enum Kind {
        ACTION_SCHEDULED, ACTION_SUCCEEDED, ACTION_FAILED, DECISION_RECORDED, TIMER_STARTED,
        TIMER_FIRED, UPDATE_RECEIVED, EXECUTION_COMPLETED, EXECUTION_FAILED
    }

    public JournalEntry {
        Objects.requireNonNull(kind, "kind");
        if (sequence < 1) {
            throw new IllegalArgumentException("A journal sequence starts at 1: " + sequence);
        }
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JournalEntry entry && entry.sequence == sequence
                && entry.kind == kind && Objects.equals(entry.path, path)
                && Arrays.equals(entry.payload, payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, kind, path, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "JournalEntry[" + sequence + ", " + kind + ", " + path + ", " + payload.length
                + " bytes]";
    }
}
