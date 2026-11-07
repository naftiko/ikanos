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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.store.ConcurrentAppendException;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.JournalStore;
import io.ikanos.durable.store.StoreFeature;

/** Journal in process memory, with conditional appends and fencing. */
final class MemoryJournalStore implements JournalStore {

    private final Map<ExecutionId, Journal> journals = new ConcurrentHashMap<>();

    private static final class Journal {
        final List<JournalEntry> entries = new ArrayList<>();
        long fencingToken = Long.MIN_VALUE;
    }

    @Override
    public Set<StoreFeature> features() {
        return EnumSet.of(StoreFeature.TRANSACTIONAL, StoreFeature.CONDITIONAL_WRITES);
    }

    @Override
    public void append(ExecutionId executionId, long expectedSequence, long fencingToken,
            List<JournalEntry> entries) throws ConcurrentAppendException {
        Journal journal = journals.computeIfAbsent(executionId, id -> new Journal());
        synchronized (journal) {
            if (fencingToken < journal.fencingToken) {
                throw new ConcurrentAppendException("Stale fencing token " + fencingToken
                        + " for " + executionId + "; current is " + journal.fencingToken);
            }
            if (journal.entries.size() != expectedSequence) {
                throw new ConcurrentAppendException("Journal of " + executionId + " is at "
                        + journal.entries.size() + ", not " + expectedSequence);
            }
            long next = expectedSequence + 1;
            for (JournalEntry entry : entries) {
                if (entry.sequence() != next++) {
                    throw new IllegalArgumentException(
                            "Entries must continue the journal without gaps: " + entry);
                }
            }
            journal.fencingToken = fencingToken;
            journal.entries.addAll(entries);
        }
    }

    @Override
    public List<JournalEntry> read(ExecutionId executionId) {
        Journal journal = journals.get(executionId);
        if (journal == null) {
            return List.of();
        }
        synchronized (journal) {
            return List.copyOf(journal.entries);
        }
    }

    @Override
    public void truncate(ExecutionId executionId) {
        journals.remove(executionId);
    }
}
