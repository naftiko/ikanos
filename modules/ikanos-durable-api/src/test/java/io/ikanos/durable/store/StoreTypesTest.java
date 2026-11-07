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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;

public class StoreTypesTest {

    private static final ExecutionId ID = new ExecutionId("exec-1");
    private static final NodePath PATH = new NodePath("steps/voyage");

    @Test
    void journalEntryShouldNotExposeItsPayloadArray() {
        byte[] bytes = {1, 2, 3};
        JournalEntry entry = new JournalEntry(1, JournalEntry.Kind.ACTION_SUCCEEDED, PATH, bytes);
        bytes[0] = 9;
        entry.payload()[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, entry.payload());
    }

    @Test
    void journalEntriesShouldCompareTheirPayloadByContent() {
        JournalEntry a = new JournalEntry(1, JournalEntry.Kind.ACTION_SUCCEEDED, PATH, new byte[] {1});
        JournalEntry b = new JournalEntry(1, JournalEntry.Kind.ACTION_SUCCEEDED, PATH, new byte[] {1});
        JournalEntry c = new JournalEntry(1, JournalEntry.Kind.ACTION_SUCCEEDED, PATH, new byte[] {2});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertTrue(a.toString().contains("1 bytes"));
    }

    @Test
    void journalEntryShouldRejectSequenceZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new JournalEntry(0, JournalEntry.Kind.TIMER_FIRED, PATH, null));
    }

    @Test
    void journalEntryShouldDefaultAMissingPayloadToEmpty() {
        assertEquals(0,
                new JournalEntry(1, JournalEntry.Kind.EXECUTION_COMPLETED, null, null).payload().length);
    }

    @Test
    void terminalStatusesShouldBeCompletedFailedAndCancelled() {
        assertTrue(ExecutionStatus.COMPLETED.isTerminal());
        assertTrue(ExecutionStatus.FAILED.isTerminal());
        assertTrue(ExecutionStatus.CANCELLED.isTerminal());
        assertFalse(ExecutionStatus.RUNNING.isTerminal());
        assertFalse(ExecutionStatus.SUSPENDED.isTerminal());
    }

    @Test
    void executionRecordShouldCopyItsLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("voyage", "VOY-2026-042");
        ExecutionRecord record = new ExecutionRecord(ID, "sha256:abc",
                "mcp:shipyard-ops/clear-for-departure", ExecutionStatus.RUNNING, Instant.EPOCH,
                null, labels);
        labels.clear();
        assertEquals(Map.of("voyage", "VOY-2026-042"), record.labels());
    }

    @Test
    void executionRecordShouldDefaultMissingLabelsToEmpty() {
        assertTrue(new ExecutionRecord(ID, "sha256:abc", null, ExecutionStatus.RUNNING,
                Instant.EPOCH, null, null).labels().isEmpty());
    }

    @Test
    void storesShouldRequireEveryStore() {
        assertThrows(NullPointerException.class,
                () -> new Stores(null, null, null, null, null));
    }

    @Test
    void findShouldReturnNothingForAStoreTypeWithoutModule() {
        assertTrue(StoreProvider.find("raft").isEmpty());
        assertFalse(StoreProvider.available().contains("raft"));
    }

    @Test
    void leaseAndBlobRefShouldRequireTheirFields() {
        assertThrows(NullPointerException.class, () -> new Lease(ID, null, 1, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new BlobRef(null));
    }
}
