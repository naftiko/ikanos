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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.ikanos.durable.ExecutionId;

/**
 * Stores that crash once, at a chosen journal boundary: the crash-injection harness of the
 * durable engine. The crash is a {@link Crash}, an {@link Error} no action failure handler
 * catches, so it leaves the stores exactly as a process killed at that point would.
 */
public final class FaultInjectingStores {

    /** Where the crash happens, around the append of one kind of journal entry. */
    public enum Boundary {
        /** After an execution is indexed, before its input is journaled. */
        BEFORE_STARTED(JournalEntry.Kind.EXECUTION_STARTED, false),
        /** After an action is journaled as scheduled, before it is invoked. */
        AFTER_SCHEDULED(JournalEntry.Kind.ACTION_SCHEDULED, true),
        /** After an action was invoked, before its outcome is journaled. */
        BEFORE_OUTCOME(JournalEntry.Kind.ACTION_SUCCEEDED, false),
        /** After an action's outcome is journaled, before the next entry. */
        AFTER_OUTCOME(JournalEntry.Kind.ACTION_SUCCEEDED, true),
        /** After the execution's completion is journaled, before the index records it. */
        AFTER_COMPLETED(JournalEntry.Kind.EXECUTION_COMPLETED, true);

        private final JournalEntry.Kind kind;
        private final boolean after;

        Boundary(JournalEntry.Kind kind, boolean after) {
            this.kind = kind;
            this.after = after;
        }
    }

    /** The simulated crash. */
    public static final class Crash extends Error {
        private static final long serialVersionUID = 1L;

        Crash(String message) {
            super(message);
        }
    }

    private FaultInjectingStores() {
    }

    /**
     * Wraps {@code stores} so that the {@code occurrence}-th append (1-based) of the boundary's
     * entry kind crashes, once. Every other call passes through.
     */
    public static Stores wrap(Stores stores, Boundary boundary, int occurrence) {
        return new Stores(new CrashingJournal(stores.journal(), boundary, occurrence),
                stores.index(), stores.queue(), stores.inbox(), stores.blobs(), stores::close);
    }

    private static final class CrashingJournal implements JournalStore {

        private final JournalStore delegate;
        private final Boundary boundary;
        private final int occurrence;
        private final AtomicInteger seen = new AtomicInteger();
        private final AtomicBoolean fired = new AtomicBoolean();

        CrashingJournal(JournalStore delegate, Boundary boundary, int occurrence) {
            this.delegate = delegate;
            this.boundary = boundary;
            this.occurrence = occurrence;
        }

        @Override
        public Set<StoreFeature> features() {
            return delegate.features();
        }

        @Override
        public void append(ExecutionId executionId, long expectedSequence, long fencingToken,
                List<JournalEntry> entries) throws ConcurrentAppendException {
            boolean matches = !fired.get() && entries.stream()
                    .anyMatch(e -> e.kind() == boundary.kind)
                    && seen.incrementAndGet() == occurrence;
            if (matches && !boundary.after) {
                fired.set(true);
                throw new Crash("Crash " + boundary + " #" + occurrence);
            }
            delegate.append(executionId, expectedSequence, fencingToken, entries);
            if (matches) {
                fired.set(true);
                throw new Crash("Crash " + boundary + " #" + occurrence);
            }
        }

        @Override
        public List<JournalEntry> read(ExecutionId executionId) {
            return delegate.read(executionId);
        }

        @Override
        public void truncate(ExecutionId executionId) {
            delegate.truncate(executionId);
        }
    }
}
