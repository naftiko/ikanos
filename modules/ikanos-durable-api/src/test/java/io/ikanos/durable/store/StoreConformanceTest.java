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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.NodePath;

/**
 * The conformance suite every store module runs: the contract of the five storage interfaces, and
 * each {@link StoreFeature} a store declares. A store module extends this class and opens its
 * stores in {@link #open(Clock)}; a store declaring {@link StoreFeature#DURABLE} also implements
 * {@link #reopen(Stores, Clock)}.
 */
public abstract class StoreConformanceTest {

    protected static final ExecutionId ID = new ExecutionId("exec-1");
    protected static final ExecutionId OTHER = new ExecutionId("exec-2");
    protected static final NodePath PATH = new NodePath("steps/confirm-hazardous");
    protected static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    protected final MutableClock clock = new MutableClock(NOW);
    protected Stores stores;

    /** Opens fresh, empty stores that read store time from {@code clock}. */
    protected abstract Stores open(Clock clock);

    /**
     * Closes {@code stores} and opens the same data again. Only stores that declare
     * {@link StoreFeature#DURABLE} implement it.
     */
    protected Stores reopen(Stores stores, Clock clock) {
        throw new UnsupportedOperationException("This store does not survive a restart");
    }

    @BeforeEach
    void openStores() {
        stores = open(clock);
    }

    @AfterEach
    void closeStores() {
        if (stores != null) {
            stores.close();
        }
    }

    protected static JournalEntry entry(long sequence) {
        return new JournalEntry(sequence, JournalEntry.Kind.ACTION_SUCCEEDED, PATH,
                new byte[] {(byte) sequence});
    }

    protected static ExecutionRecord record(ExecutionId id, Instant createdAt) {
        return new ExecutionRecord(id, "sha256:tree", "mcp:shipyard-ops/clear-for-departure",
                ExecutionStatus.RUNNING, createdAt, createdAt.plusSeconds(1800),
                Map.of("taskId", "task-" + id.value()));
    }

    // Journal

    @Test
    void journalShouldAppendAndReadInOrder() throws ConcurrentAppendException {
        stores.journal().append(ID, 0, 0, List.of(entry(1), entry(2)));
        stores.journal().append(ID, 2, 0, List.of(entry(3)));

        assertEquals(List.of(entry(1), entry(2), entry(3)), stores.journal().read(ID));
    }

    @Test
    void journalShouldReadAnUnknownExecutionAsEmpty() {
        assertTrue(stores.journal().read(ID).isEmpty());
    }

    @Test
    void journalShouldKeepEveryEntryField() throws ConcurrentAppendException {
        JournalEntry decision = new JournalEntry(1, JournalEntry.Kind.DECISION_RECORDED,
                new NodePath("steps/inspection").child("then"), new byte[] {9, 8, 7});
        JournalEntry completed = new JournalEntry(2, JournalEntry.Kind.EXECUTION_COMPLETED, null,
                new byte[0]);
        stores.journal().append(ID, 0, 0, List.of(decision, completed));

        assertEquals(List.of(decision, completed), stores.journal().read(ID));
    }

    @Test
    void journalShouldRejectAnAppendAtAStaleSequence() throws ConcurrentAppendException {
        assumeTrue(stores.journal().features().contains(StoreFeature.CONDITIONAL_WRITES));
        stores.journal().append(ID, 0, 0, List.of(entry(1)));

        assertThrows(ConcurrentAppendException.class,
                () -> stores.journal().append(ID, 0, 0, List.of(entry(1))));
        assertEquals(List.of(entry(1)), stores.journal().read(ID));
    }

    @Test
    void journalShouldRejectAStaleFencingToken() throws ConcurrentAppendException {
        assumeTrue(stores.journal().features().contains(StoreFeature.CONDITIONAL_WRITES));
        stores.journal().append(ID, 0, 5, List.of(entry(1)));

        assertThrows(ConcurrentAppendException.class,
                () -> stores.journal().append(ID, 1, 4, List.of(entry(2))));
        stores.journal().append(ID, 1, 6, List.of(entry(2)));
        assertEquals(2, stores.journal().read(ID).size());
    }

    @Test
    void journalShouldRejectAGapInSequences() {
        assertThrows(IllegalArgumentException.class,
                () -> stores.journal().append(ID, 0, 0, List.of(entry(2))));
    }

    @Test
    void journalShouldAppendABatchWhollyOrNotAtAll() {
        assumeTrue(stores.journal().features().contains(StoreFeature.TRANSACTIONAL));

        assertThrows(IllegalArgumentException.class,
                () -> stores.journal().append(ID, 0, 0, List.of(entry(1), entry(3))));
        assertTrue(stores.journal().read(ID).isEmpty());
    }

    @Test
    void journalShouldTruncateOneExecutionOnly() throws ConcurrentAppendException {
        stores.journal().append(ID, 0, 0, List.of(entry(1)));
        stores.journal().append(OTHER, 0, 0, List.of(entry(1)));

        stores.journal().truncate(ID);

        assertTrue(stores.journal().read(ID).isEmpty());
        assertEquals(List.of(entry(1)), stores.journal().read(OTHER));
    }

    // Execution index

    @Test
    void indexShouldCreateOnceAndTrackStatus() {
        ExecutionRecord record = record(ID, NOW);

        assertTrue(stores.index().create(record));
        assertFalse(stores.index().create(record));
        stores.index().updateStatus(ID, ExecutionStatus.SUSPENDED);

        assertEquals(ExecutionStatus.SUSPENDED, stores.index().find(ID).orElseThrow().status());
        assertEquals(1, stores.index().list(ExecutionStatus.SUSPENDED, 10).size());
        assertTrue(stores.index().list(ExecutionStatus.RUNNING, 10).isEmpty());
    }

    @Test
    void indexShouldKeepEveryRecordField() {
        ExecutionRecord record = record(ID, NOW);
        stores.index().create(record);

        assertEquals(record, stores.index().find(ID).orElseThrow());
        assertTrue(stores.index().find(OTHER).isEmpty());
    }

    @Test
    void indexShouldKeepARecordWithoutDeadlineEntryOrLabels() {
        ExecutionRecord record = new ExecutionRecord(ID, "sha256:tree", null,
                ExecutionStatus.RUNNING, NOW, null, Map.of());
        stores.index().create(record);

        assertEquals(record, stores.index().find(ID).orElseThrow());
    }

    @Test
    void indexShouldListOldestFirstUpToTheLimit() {
        stores.index().create(record(OTHER, NOW.plusSeconds(10)));
        stores.index().create(record(ID, NOW));
        stores.index().create(record(new ExecutionId("exec-3"), NOW.plusSeconds(20)));

        List<ExecutionRecord> listed = stores.index().list(ExecutionStatus.RUNNING, 2);

        assertEquals(List.of(ID, OTHER),
                listed.stream().map(ExecutionRecord::executionId).toList());
    }

    // Signal inbox

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
    void inboxShouldKeepUpdatesInDeliveryOrderPerExecution() {
        NodePath second = new NodePath("steps/accept-fee");
        stores.inbox().deliver(ID, second, new byte[] {2});
        stores.inbox().deliver(ID, PATH, new byte[] {1});
        stores.inbox().deliver(OTHER, PATH, new byte[] {3});

        assertEquals(List.of(second, PATH), List.copyOf(stores.inbox().pending(ID).keySet()));
        assertEquals(1, stores.inbox().pending(OTHER).size());
    }

    // Work queue

    @Test
    void queueShouldLeaseDueExecutionsWithGrowingFencingTokens() {
        stores.queue().enqueue(ID, NOW);
        stores.queue().enqueue(OTHER, NOW.plusSeconds(60));

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
    void queueShouldHandAnExpiredLeaseToAnotherOwner() {
        stores.queue().enqueue(ID, NOW);
        Lease first = stores.queue().claim("pod-a", Duration.ofSeconds(30)).orElseThrow();

        clock.advance(Duration.ofSeconds(31));
        assertFalse(stores.queue().renew(first, Duration.ofSeconds(30)));
        stores.queue().enqueue(ID, clock.instant());
        Lease second = stores.queue().claim("pod-b", Duration.ofSeconds(30)).orElseThrow();

        assertEquals("pod-b", second.owner());
        assertTrue(second.fencingToken() > first.fencingToken());
    }

    @Test
    void queueShouldClaimTheEarliestDueExecutionFirst() {
        stores.queue().enqueue(OTHER, NOW.minusSeconds(5));
        stores.queue().enqueue(ID, NOW.minusSeconds(10));

        assertEquals(ID, stores.queue().claim("pod-a", Duration.ofSeconds(30)).orElseThrow()
                .executionId());
    }

    // Blob store

    @Test
    void blobsShouldBeStoredPerExecution() {
        BlobRef ref = stores.blobs().put(ID, new byte[] {7, 8});
        BlobRef other = stores.blobs().put(OTHER, new byte[] {9});
        assertArrayEquals(new byte[] {7, 8}, stores.blobs().get(ref));

        stores.blobs().deleteAll(ID);

        assertThrows(IllegalArgumentException.class, () -> stores.blobs().get(ref));
        assertArrayEquals(new byte[] {9}, stores.blobs().get(other));
    }

    // Durability

    @Test
    void storesShouldReadBackEverythingAfterAReopen() throws ConcurrentAppendException {
        assumeTrue(stores.journal().features().contains(StoreFeature.DURABLE));
        ExecutionRecord record = record(ID, NOW);
        stores.index().create(record);
        stores.journal().append(ID, 0, 3, List.of(entry(1), entry(2)));
        stores.inbox().deliver(ID, PATH, new byte[] {4});
        BlobRef ref = stores.blobs().put(ID, new byte[] {5, 6});
        stores.queue().enqueue(ID, NOW);

        stores = reopen(stores, clock);

        assertEquals(record, stores.index().find(ID).orElseThrow());
        assertEquals(List.of(entry(1), entry(2)), stores.journal().read(ID));
        assertThrows(ConcurrentAppendException.class,
                () -> stores.journal().append(ID, 2, 2, List.of(entry(3))));
        assertArrayEquals(new byte[] {4}, stores.inbox().pending(ID).get(PATH));
        assertArrayEquals(new byte[] {5, 6}, stores.blobs().get(ref));
        assertEquals(ID, stores.queue().claim("pod-a", Duration.ofSeconds(30)).orElseThrow()
                .executionId());
    }

    /** A clock the tests move forward by hand. */
    public static final class MutableClock extends Clock {

        private volatile Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
