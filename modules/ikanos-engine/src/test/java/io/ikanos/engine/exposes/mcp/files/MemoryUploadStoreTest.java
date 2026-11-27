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
}
