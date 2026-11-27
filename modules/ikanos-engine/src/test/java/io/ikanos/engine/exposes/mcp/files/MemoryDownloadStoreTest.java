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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MemoryDownloadStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private static DownloadStore.StoredDownload file(int size, Instant expiresAt) {
        return file("alice", size, expiresAt);
    }

    private static DownloadStore.StoredDownload file(String principal, int size,
            Instant expiresAt) {
        return new DownloadStore.StoredDownload(principal, "f.bin", "application/octet-stream",
                new byte[size], expiresAt);
    }

    @Test
    void takeShouldReturnNothingOnceTheFileHasExpired() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 100);
        store.put("t", file(1, NOW.plusSeconds(60)), NOW);

        assertTrue(store.take("t", "alice", NOW.plusSeconds(60)).isEmpty());
    }

    @Test
    void takeShouldLeaveTheFileOfAnotherPrincipal() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 100);
        store.put("t", file(1, NOW.plusSeconds(60)), NOW);

        assertTrue(store.take("t", "mallory", NOW).isEmpty());
        assertTrue(store.take("t", "alice", NOW).isPresent());
    }

    @Test
    void putShouldRefuseAFileBeyondTheByteBound() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 100);

        assertTrue(store.put("a", file(60, NOW.plusSeconds(60)), NOW));
        assertFalse(store.put("b", file(60, NOW.plusSeconds(60)), NOW));
    }

    @Test
    void putShouldMakeRoomByDroppingExpiredFiles() {
        MemoryDownloadStore store = new MemoryDownloadStore(1, 100, 100);
        store.put("a", file(60, NOW.plusSeconds(1)), NOW);

        assertTrue(store.put("b", file(60, NOW.plusSeconds(120)), NOW.plusSeconds(1)));
        assertEquals(1, store.size());
    }

    @Test
    void takeShouldFreeTheBytesOfTheFile() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 100);
        store.put("a", file(60, NOW.plusSeconds(60)), NOW);
        store.take("a", "alice", NOW);

        assertTrue(store.put("b", file(60, NOW.plusSeconds(60)), NOW));
    }

    @Test
    void putShouldRefuseACallerBeyondItsShare() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 50);
        store.put("a", file("alice", 40, NOW.plusSeconds(60)), NOW);

        assertFalse(store.put("b", file("alice", 20, NOW.plusSeconds(60)), NOW));
        assertTrue(store.put("c", file("bob", 20, NOW.plusSeconds(60)), NOW),
                "Another caller still has room");
    }

    @Test
    void takeShouldGiveTheCallerItsShareBack() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 50);
        store.put("a", file("alice", 40, NOW.plusSeconds(60)), NOW);
        store.take("a", "alice", NOW);

        assertTrue(store.put("b", file("alice", 40, NOW.plusSeconds(60)), NOW));
    }

    @Test
    void sweepShouldDropExpiredFilesWithoutAPutOrTake() {
        MemoryDownloadStore store = new MemoryDownloadStore(10, 100, 100);
        store.put("a", file(10, NOW.plusSeconds(1)), NOW);

        store.sweep(NOW.plusSeconds(1));

        assertEquals(0, store.size());
    }

    @Test
    void maxBytesForShouldTakeAnEighthOfTheHeapUpTo256MiB() {
        long mib = 1024L * 1024;

        assertEquals(32 * mib, MemoryDownloadStore.maxBytesFor(256 * mib));
        assertEquals(256 * mib, MemoryDownloadStore.maxBytesFor(8192 * mib));
    }

    @Test
    void sharedShouldBeOneStoreForTheProcessWithinTheHeapBound() {
        MemoryDownloadStore store = MemoryDownloadStore.shared();

        assertTrue(store == MemoryDownloadStore.shared());
        assertEquals(MemoryDownloadStore.maxBytesFor(Runtime.getRuntime().maxMemory()),
                store.maxBytes());
    }
}
