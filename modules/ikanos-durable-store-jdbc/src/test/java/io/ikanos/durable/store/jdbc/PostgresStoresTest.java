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
package io.ikanos.durable.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.ikanos.durable.store.ConcurrentAppendException;
import io.ikanos.durable.store.Lease;
import io.ikanos.durable.store.StoreConformanceTest;
import io.ikanos.durable.store.StoreFeature;
import io.ikanos.durable.store.Stores;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * The conformance suite on a real PostgreSQL server, one fresh schema per test, and the races
 * between replicas that only a shared store has: each replica is a separate {@link Stores} on the
 * same database, with its own connection pool.
 */
class PostgresStoresTest extends StoreConformanceTest {

    private static final int RACERS = 8;
    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    private static EmbeddedPostgres server;

    private String url;
    private final List<Stores> replicas = new ArrayList<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Override
    protected Stores open(Clock clock) {
        String schema = "conformance_" + SCHEMAS.incrementAndGet();
        try (Connection c = server.getPostgresDatabase().getConnection();
                Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        url = server.getJdbcUrl("postgres", "postgres") + "&currentSchema=" + schema;
        return PostgresStoreProvider.openStores(url, null, null, clock);
    }

    @Override
    protected Stores reopen(Stores stores, Clock clock) {
        stores.close();
        return PostgresStoreProvider.openStores(url, null, null, clock);
    }

    /** Another replica on the same database: its own pool, the same tables. */
    private Stores replica() {
        Stores replica = PostgresStoreProvider.openStores(url, null, null, clock);
        replicas.add(replica);
        return replica;
    }

    @org.junit.jupiter.api.AfterEach
    void closeReplicas() {
        replicas.forEach(Stores::close);
    }

    /** Runs {@code work} on {@link #RACERS} threads released together, and returns the results. */
    private static <T> List<T> race(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void journalShouldDeclareTheStoreShared() {
        assertTrue(stores.journal().features().contains(StoreFeature.SHARED));
    }

    @Test
    void providerShouldBeFoundByItsType() {
        assertTrue(io.ikanos.durable.store.StoreProvider.find("postgres").isPresent());
    }

    @Test
    void indexShouldListOnlyTheExecutionsWithoutALiveLease() {
        stores.index().create(record(ID, NOW));
        stores.index().create(record(OTHER, NOW.plusSeconds(1)));
        stores.queue().acquire(ID, "pod-a", Duration.ofSeconds(30)).orElseThrow();

        assertEquals(List.of(OTHER), stores.index().listUnleased(
                io.ikanos.durable.store.ExecutionStatus.RUNNING, "taskId", NOW, 1).stream()
                .map(io.ikanos.durable.store.ExecutionRecord::executionId).toList(),
                "A leased execution never takes the place of an unleased one");
        assertEquals(2, stores.index().listUnleased(
                io.ikanos.durable.store.ExecutionStatus.RUNNING, "taskId",
                NOW.plusSeconds(30), 10).size(), "An expired lease no longer counts");
    }

    @Test
    void indexShouldListOnlyTheUnleasedExecutionsWithUpdatesWaiting() {
        stores.index().create(record(ID, NOW));
        stores.index().create(record(OTHER, NOW.plusSeconds(1)));
        stores.inbox().deliver(OTHER, PATH, new byte[] {1});

        assertEquals(List.of(OTHER), stores.index().listUnleasedWithUpdates(
                io.ikanos.durable.store.ExecutionStatus.RUNNING, "taskId", NOW, 1).stream()
                .map(io.ikanos.durable.store.ExecutionRecord::executionId).toList(),
                "An execution without updates never takes the place of one with updates");
        stores.inbox().acknowledge(OTHER, PATH);
        assertTrue(stores.index().listUnleasedWithUpdates(
                io.ikanos.durable.store.ExecutionStatus.RUNNING, "taskId", NOW, 10).isEmpty());
    }

    @Test
    void acquireShouldGrantOneLeaseWhenReplicasRaceForAnExecution() throws Exception {
        Stores other = replica();
        AtomicInteger turn = new AtomicInteger();

        List<Optional<Lease>> leases = race(() -> (turn.incrementAndGet() % 2 == 0 ? stores
                : other).queue().acquire(ID, "pod-" + turn.get(), Duration.ofSeconds(30)));

        assertEquals(1, leases.stream().filter(Optional::isPresent).count());
    }

    @Test
    void claimShouldHandAQueuedExecutionToOneReplicaWhenReplicasRace() throws Exception {
        Stores other = replica();
        stores.queue().enqueue(ID, NOW);
        AtomicInteger turn = new AtomicInteger();

        List<Optional<Lease>> leases = race(() -> (turn.incrementAndGet() % 2 == 0 ? stores
                : other).queue().claim("pod-" + turn.get(), Duration.ofSeconds(30)));

        assertEquals(1, leases.stream().filter(Optional::isPresent).count());
    }

    @Test
    void indexShouldCreateAnExecutionOnceWhenReplicasRace() throws Exception {
        Stores other = replica();
        AtomicInteger turn = new AtomicInteger();

        List<Boolean> created = race(() -> (turn.incrementAndGet() % 2 == 0 ? stores : other)
                .index().create(record(ID, NOW)));

        assertEquals(1, created.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, stores.index().findByLabel("taskId", "task-" + ID.value()).size());
    }

    @Test
    void inboxShouldDeliverAnUpdateOnceWhenReplicasRace() throws Exception {
        Stores other = replica();
        AtomicInteger turn = new AtomicInteger();

        List<Boolean> delivered = race(() -> (turn.incrementAndGet() % 2 == 0 ? stores : other)
                .inbox().deliver(ID, PATH, new byte[] {1}));

        assertEquals(1, delivered.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, stores.inbox().pending(ID).size());
    }

    @Test
    void journalShouldAcceptOneFirstAppendWhenReplicasRace() throws Exception {
        Stores other = replica();
        AtomicInteger turn = new AtomicInteger();

        List<Boolean> appended = race(() -> {
            try {
                (turn.incrementAndGet() % 2 == 0 ? stores : other).journal()
                        .append(ID, 0, 1, List.of(entry(1)));
                return true;
            } catch (ConcurrentAppendException e) {
                return false;
            }
        });

        assertEquals(1, appended.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, stores.journal().read(ID).size());
    }

    @Test
    void journalShouldAcceptOneAppendAtASequenceWhenReplicasRace() throws Exception {
        Stores other = replica();
        stores.journal().append(ID, 0, 1, List.of(entry(1)));
        AtomicInteger turn = new AtomicInteger();

        List<Boolean> appended = race(() -> {
            try {
                (turn.incrementAndGet() % 2 == 0 ? stores : other).journal()
                        .append(ID, 1, 1, List.of(entry(2)));
                return true;
            } catch (ConcurrentAppendException e) {
                return false;
            }
        });

        assertEquals(1, appended.stream().filter(Boolean::booleanValue).count());
        assertEquals(2, stores.journal().read(ID).size());
    }

    @Test
    void openShouldMigrateOnceWhenReplicasStartTogether() throws Exception {
        String schema = "startup_" + SCHEMAS.incrementAndGet();
        try (Connection c = server.getPostgresDatabase().getConnection();
                Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        String shared = server.getJdbcUrl("postgres", "postgres") + "&currentSchema=" + schema;

        List<Stores> opened = race(() -> PostgresStoreProvider.openStores(shared, null, null,
                clock));
        replicas.addAll(opened);

        for (Stores replica : opened) {
            assertTrue(replica.journal().read(ID).isEmpty());
        }
    }

    @Test
    void openShouldNameTheStoreWithoutItsQueryWhenTheServerIsUnreachable() {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> PostgresStoreProvider.openStores(
                        "jdbc:postgresql://127.0.0.1:1/none?password=secret", null, null, clock));

        assertTrue(e.getMessage().startsWith(
                "Cannot open the PostgreSQL store at jdbc:postgresql://127.0.0.1:1/none:"),
                e.getMessage());
        assertTrue(!e.getMessage().contains("secret"), e.getMessage());
    }
}
