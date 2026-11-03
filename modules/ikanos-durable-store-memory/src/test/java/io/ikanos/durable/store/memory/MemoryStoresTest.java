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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.BlobRef;
import io.ikanos.durable.store.ConcurrentAppendException;
import io.ikanos.durable.store.ExecutionRecord;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.Lease;
import io.ikanos.durable.store.StoreFeature;
import io.ikanos.durable.store.StoreProvider;
import io.ikanos.durable.store.Stores;

public class MemoryStoresTest {

    private static final ExecutionId ID = new ExecutionId("exec-1");
    private static final NodePath PATH = new NodePath("steps/confirm-hazardous");
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final Stores stores = MemoryStoreProvider.openStores(Clock.fixed(NOW, ZoneOffset.UTC));

    private static JournalEntry entry(long sequence) {
        return new JournalEntry(sequence, JournalEntry.Kind.ACTION_SUCCEEDED, PATH, new byte[] {1});
    }

    @Test
    void providerShouldBeDiscoveredAsTheMemoryStore() {
        StoreProvider provider = StoreProvider.find("memory").orElseThrow();
        assertEquals("io.ikanos:ikanos-durable-store-memory", provider.module());
        assertTrue(StoreProvider.available().contains("memory"));
        assertTrue(provider.open(Map.of()).journal().read(ID).isEmpty());
    }

    @Test
    void journalShouldAppendAndReadInOrder() throws ConcurrentAppendException {
        stores.journal().append(ID, 0, 0, List.of(entry(1), entry(2)));
        stores.journal().append(ID, 2, 0, List.of(entry(3)));

        assertEquals(List.of(entry(1), entry(2), entry(3)), stores.journal().read(ID));
        assertTrue(stores.journal().features().contains(StoreFeature.CONDITIONAL_WRITES));
    }

    @Test
    void journalShouldRejectAnAppendAtAStaleSequence() throws ConcurrentAppendException {
        stores.journal().append(ID, 0, 0, List.of(entry(1)));

        assertThrows(ConcurrentAppendException.class,
                () -> stores.journal().append(ID, 0, 0, List.of(entry(1))));
    }

    @Test
    void journalShouldRejectAStaleFencingToken() throws ConcurrentAppendException {
        stores.journal().append(ID, 0, 5, List.of(entry(1)));

        assertThrows(ConcurrentAppendException.class,
                () -> stores.journal().append(ID, 1, 4, List.of(entry(2))));
    }

    @Test
    void journalShouldRejectAGapInSequences() {
        assertThrows(IllegalArgumentException.class,
                () -> stores.journal().append(ID, 0, 0, List.of(entry(2))));
    }

    @Test
    void journalShouldForgetATruncatedExecution() throws ConcurrentAppendException {
        stores.journal().append(ID, 0, 0, List.of(entry(1)));
        stores.journal().truncate(ID);
        assertTrue(stores.journal().read(ID).isEmpty());
    }

    @Test
    void indexShouldCreateOnceAndTrackStatus() {
        ExecutionRecord record = new ExecutionRecord(ID, "sha256:tree", "mcp:shipyard-ops",
                ExecutionStatus.RUNNING, NOW, null, Map.of());

        assertTrue(stores.index().create(record));
        assertFalse(stores.index().create(record));
        stores.index().updateStatus(ID, ExecutionStatus.SUSPENDED);

        assertEquals(ExecutionStatus.SUSPENDED, stores.index().find(ID).orElseThrow().status());
        assertEquals(1, stores.index().list(ExecutionStatus.SUSPENDED, 10).size());
        assertTrue(stores.index().list(ExecutionStatus.RUNNING, 10).isEmpty());
    }

    @Test
    void inboxShouldDeliverEachUpdateOnce() {
        assertTrue(stores.inbox().deliver(ID, PATH, new byte[] {1}));
        assertFalse(stores.inbox().deliver(ID, PATH, new byte[] {2}));
        assertArrayEquals(new byte[] {1}, stores.inbox().pending(ID).get(PATH));

        stores.inbox().acknowledge(ID, PATH);

        assertTrue(stores.inbox().pending(ID).isEmpty());
        assertFalse(stores.inbox().deliver(ID, PATH, new byte[] {3}));
    }

    @Test
    void queueShouldLeaseDueExecutionsWithGrowingFencingTokens() {
        stores.queue().enqueue(ID, NOW);
        stores.queue().enqueue(new ExecutionId("later"), NOW.plusSeconds(60));

        Lease lease = stores.queue().claim("pod-a", Duration.ofSeconds(30)).orElseThrow();

        assertEquals(ID, lease.executionId());
        assertTrue(stores.queue().claim("pod-b", Duration.ofSeconds(30)).isEmpty());
        assertTrue(stores.queue().renew(lease, Duration.ofSeconds(30)));

        stores.queue().release(lease);
        stores.queue().enqueue(ID, NOW);
        Lease next = stores.queue().claim("pod-b", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(next.fencingToken() > lease.fencingToken());
        assertFalse(stores.queue().renew(lease, Duration.ofSeconds(30)));
    }

    @Test
    void blobsShouldBeStoredPerExecution() {
        BlobRef ref = stores.blobs().put(ID, new byte[] {7, 8});
        assertArrayEquals(new byte[] {7, 8}, stores.blobs().get(ref));

        stores.blobs().deleteAll(ID);

        assertThrows(IllegalArgumentException.class, () -> stores.blobs().get(ref));
    }
}
