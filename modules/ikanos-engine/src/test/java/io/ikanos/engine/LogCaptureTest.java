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
package io.ikanos.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.spi.ILoggingEvent;

class LogCaptureTest {

    /** Reads after which the background writer stops, in the concurrency test. */
    private static final int READS = 100;
    /** Upper bound on the background writer's lines, whatever the reader's pace. */
    private static final int MAX_LINES = 50_000;

    @Test
    void messagesShouldDescribeCapturedEvents() {
        try (LogCapture logs = new LogCapture()) {
            Context.getCurrentLogger().warning("first");
            Context.getCurrentLogger().log(Level.SEVERE, "second",
                    new IllegalStateException("boom"));
            List<String> lines = logs.messages();
            assertTrue(lines.contains("first"), lines.toString());
            assertTrue(lines.contains("second | java.lang.IllegalStateException: boom"),
                    lines.toString());
        }
    }

    @Test
    void eventsShouldReturnASnapshotThatLaterLogsDoNotChange() {
        try (LogCapture logs = new LogCapture()) {
            // Another thread may log to the same logger at any time (issue #816), so assert on
            // what this test logs and on sizes relative to the snapshot, never on exact counts.
            Context.getCurrentLogger().warning("snapshot-before");
            List<ILoggingEvent> snapshot = logs.events();
            int sizeAtSnapshot = snapshot.size();
            Context.getCurrentLogger().warning("snapshot-after");

            assertEquals(sizeAtSnapshot, snapshot.size());
            assertTrue(formatted(snapshot).contains("snapshot-before"), snapshot.toString());
            assertFalse(formatted(snapshot).contains("snapshot-after"), snapshot.toString());
            assertTrue(logs.events().size() > sizeAtSnapshot);
            assertTrue(logs.messages().contains("snapshot-after"), logs.messages().toString());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        }
    }

    /**
     * Issue #816: reading captured logs while another thread (e.g. a server left running by an
     * earlier test) logs to the same logger must not throw ConcurrentModificationException.
     *
     * <p>Both sides are bounded: each read copies the whole capture, so an unbounded writer
     * makes the test quadratic in time and memory. The writer keeps logging until the reader
     * has done {@link #READS} reads (or {@link #MAX_LINES} lines are written), and the reader
     * keeps reading until the writer is done, so every read overlaps the writes.
     */
    @Test
    void readingShouldBeSafeWhileAnotherThreadLogs() throws Exception {
        Logger logger = Context.getCurrentLogger();
        ch.qos.logback.classic.Logger backend =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logger.getName());
        boolean additive = backend.isAdditive();
        // Keep the background lines out of the console and the test report.
        backend.setAdditive(false);
        AtomicInteger readsDone = new AtomicInteger();
        AtomicBoolean writing = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        try (LogCapture logs = new LogCapture()) {
            Thread writer = new Thread(() -> {
                started.countDown();
                try {
                    for (int line = 0; line < MAX_LINES && readsDone.get() < READS
                            && writing.get(); line++) {
                        logger.warning("background");
                    }
                } finally {
                    writing.set(false);
                }
            }, "log-capture-writer");
            writer.setDaemon(true);
            writer.start();
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertDoesNotThrow(() -> {
                    while (writing.get()) {
                        logs.messages();
                        logs.events().stream().filter(e -> e.getThrowableProxy() != null)
                                .count();
                        readsDone.incrementAndGet();
                    }
                });
            } finally {
                writing.set(false);
                writer.join(5_000);
            }
            // Without these, the test would also pass if the writer never logged, or if no
            // read happened while it was logging.
            assertTrue(logs.messages().contains("background"),
                    "the background thread never logged, so nothing was tested");
            assertTrue(readsDone.get() > 0, "no read overlapped the background logging");
        } finally {
            backend.setAdditive(additive);
        }
    }

    private static List<String> formatted(List<ILoggingEvent> events) {
        return events.stream().map(event -> event.getFormattedMessage()).toList();
    }
}
