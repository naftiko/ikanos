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
package io.ikanos.engine.exposes.mcp.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.engine.exposes.mcp.files.UploadStore.Slot;
import io.ikanos.engine.exposes.mcp.files.UploadStore.Write;

class MemoryUploadStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private static Slot slot(String principal, long size) {
        return new Slot(principal, "state-1", "application/pdf", size, NOW.plusSeconds(300),
                NOW.plusSeconds(600));
    }

    @Test
    void reserveShouldTakeAllTheSlotsOfACallOrNone() {
        MemoryUploadStore store = new MemoryUploadStore(100);
        Map<String, Slot> slots = new LinkedHashMap<>();
        slots.put("a", slot("alice", 60));
        slots.put("b", slot("alice", 60));

        assertFalse(store.reserve(slots, NOW));
        assertEquals(0, store.size());
        assertTrue(store.reserve(Map.of("a", slot("alice", 60)), NOW));
        assertFalse(store.reserve(Map.of("b", slot("alice", 41)), NOW), "Over the quota");
        assertTrue(store.reserve(Map.of("b", slot("alice", 40)), NOW));
        assertEquals(100, store.reserved());
    }

    @Test
    void reserveShouldRespectTheSlotBound() {
        MemoryUploadStore store = new MemoryUploadStore(1000, 1);
        assertTrue(store.reserve(Map.of("a", slot("alice", 1)), NOW));
        assertFalse(store.reserve(Map.of("b", slot("alice", 1)), NOW));
    }

    @Test
    void writeShouldBeForTheOwnerOnlyAndOnce() {
        MemoryUploadStore store = new MemoryUploadStore(100);
        store.reserve(Map.of("a", slot("alice", 3)), NOW);

        assertEquals(Write.NOT_FOUND, store.beginWrite("a", "mallory", NOW).write());
        assertEquals(Write.NOT_FOUND, store.beginWrite("zz", "alice", NOW).write());
        assertEquals(Write.STARTED, store.beginWrite("a", "alice", NOW).write());
        assertEquals(Write.TAKEN, store.beginWrite("a", "alice", NOW).write(),
                "A write in progress takes the slot");
        store.completeWrite("a", new byte[] {1, 2, 3});
        assertEquals(Write.TAKEN, store.beginWrite("a", "alice", NOW).write());
        assertArrayEquals(new byte[] {1, 2, 3}, store.read("a", NOW).orElseThrow().bytes());
    }

    @Test
    void anAbortedWriteShouldLeaveTheSlotOpen() {
        MemoryUploadStore store = new MemoryUploadStore(100);
        store.reserve(Map.of("a", slot("alice", 3)), NOW);
        store.beginWrite("a", "alice", NOW);
        store.abortWrite("a");

        assertTrue(store.read("a", NOW).isEmpty());
        assertEquals(Write.STARTED, store.beginWrite("a", "alice", NOW).write());
    }

    @Test
    void writeShouldBeRefusedOnceTheTargetExpired() {
        MemoryUploadStore store = new MemoryUploadStore(100);
        store.reserve(Map.of("a", slot("alice", 3)), NOW);

        assertEquals(Write.NOT_FOUND,
                store.beginWrite("a", "alice", NOW.plusSeconds(300)).write());
    }

    @Test
    void readShouldEndWithTheRequestState() {
        MemoryUploadStore store = new MemoryUploadStore(100);
        store.reserve(Map.of("a", slot("alice", 1)), NOW);
        store.beginWrite("a", "alice", NOW);
        store.completeWrite("a", new byte[] {7});

        assertTrue(store.read("a", NOW.plusSeconds(599)).isPresent());
        assertTrue(store.read("a", NOW.plusSeconds(600)).isEmpty());
    }

    @Test
    void deleteShouldFreeTheQuota() {
        MemoryUploadStore store = new MemoryUploadStore(10);
        store.reserve(Map.of("a", slot("alice", 10)), NOW);
        store.delete("a");

        assertEquals(0, store.reserved());
        assertTrue(store.reserve(Map.of("b", slot("alice", 10)), NOW));
    }

    @Test
    void purgeShouldDropUnwrittenSlotsAfterTheirTargetAndAllAfterTheState() {
        MemoryUploadStore store = new MemoryUploadStore(100);
        store.reserve(Map.of("unwritten", slot("alice", 5), "written", slot("alice", 1)), NOW);
        store.beginWrite("written", "alice", NOW);
        store.completeWrite("written", new byte[] {1});

        assertEquals(0, store.purge(NOW.plusSeconds(299)));
        assertEquals(1, store.purge(NOW.plusSeconds(300)));
        assertEquals(1, store.reserved());
        assertEquals(1, store.purge(NOW.plusSeconds(600)));
        assertEquals(0, store.reserved());
    }

    @Test
    void reserveShouldMakeRoomByPurgingExpiredSlots() {
        MemoryUploadStore store = new MemoryUploadStore(10);
        store.reserve(Map.of("old", slot("alice", 10)), NOW);

        assertTrue(store.reserve(Map.of("new", slot("alice", 10)), NOW.plusSeconds(300)));
    }

    @Test
    void reserveShouldDrawFromTheProcessBudgetSharedWithDownloads() {
        FileMemoryBudget budget = new FileMemoryBudget(100, 60);
        MemoryUploadStore store = new MemoryUploadStore(1000, 10, budget, null);
        MemoryDownloadStore downloads = new MemoryDownloadStore(10, budget);

        assertTrue(downloads.put("link", new DownloadStore.StoredDownload("bob", "a.bin",
                "application/octet-stream", new byte[50], NOW.plusSeconds(60)), NOW));
        assertFalse(store.reserve(Map.of("a", slot("alice", 51)), NOW),
                "The adapter quota has room, the process budget has not");
        assertEquals(50, budget.used(), "A refused reservation takes nothing");
        assertTrue(store.reserve(Map.of("a", slot("alice", 50)), NOW));
        assertEquals(100, budget.used());

        store.delete("a");
        assertEquals(50, budget.used());
    }

    @Test
    void reserveShouldRespectTheCallerShareOfTheBudget() {
        FileMemoryBudget budget = new FileMemoryBudget(100, 30);
        MemoryUploadStore store = new MemoryUploadStore(1000, 10, budget, null);

        assertFalse(store.reserve(Map.of("a", slot("alice", 31)), NOW));
        assertTrue(store.reserve(Map.of("a", slot("alice", 30)), NOW));
        assertTrue(store.reserve(Map.of("b", slot("bob", 30)), NOW));
        store.purge(NOW.plusSeconds(300));
        assertEquals(0, budget.used(), "Purged slots give their bytes back");
    }

    @Test
    void reserveShouldRefuseDeclaredSizesWhoseSumOverflows() {
        FileMemoryBudget budget = new FileMemoryBudget(100, 100);
        MemoryUploadStore store = new MemoryUploadStore(Long.MAX_VALUE, 10, budget, null);
        Map<String, Slot> slots = new LinkedHashMap<>();
        slots.put("a", slot("alice", Long.MAX_VALUE / 2 + 1));
        slots.put("b", slot("alice", Long.MAX_VALUE / 2 + 1));

        assertFalse(store.reserve(slots, NOW));
        assertEquals(0, store.size());
        assertEquals(0, budget.used());
        assertTrue(store.reserve(Map.of("c", slot("alice", 100)), NOW),
                "Nothing was corrupted by the refused sum");
    }

    @Test
    void slotsOfTheSharedPrincipalShouldNotBeHeldToACallerShare() {
        FileMemoryBudget budget = new FileMemoryBudget(100, 25);
        MemoryUploadStore store = new MemoryUploadStore(1000, 10, budget, "anonymous");

        assertTrue(store.reserve(Map.of("a", slot("anonymous", 60)), NOW),
                "Callers the adapter cannot tell apart share the total, not one quarter");
        assertFalse(store.reserve(Map.of("b", slot("anonymous", 41)), NOW), "Over the total");
        assertFalse(store.reserve(Map.of("c", slot("alice", 26)), NOW), "Alice keeps her share");
        assertTrue(store.reserve(Map.of("c", slot("alice", 25)), NOW));

        store.delete("a");
        assertEquals(25, budget.used());
    }

    @Test
    void clearShouldDropEverySlotAndGiveTheBudgetBack() {
        FileMemoryBudget budget = new FileMemoryBudget(100, 100);
        MemoryUploadStore store = new MemoryUploadStore(1000, 10, budget, null);
        store.reserve(Map.of("a", slot("alice", 30), "b", slot("bob", 20)), NOW);
        store.beginWrite("a", "alice", NOW);

        store.clear();

        assertEquals(0, store.size());
        assertEquals(0, budget.used());
        assertFalse(store.completeWrite("a", new byte[30]), "A write in flight keeps nothing");
    }
}
