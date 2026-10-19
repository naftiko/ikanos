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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.restlet.Context;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.spi.ILoggingEvent;

class LogCaptureTest {

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
     */
    @Test
    void readingShouldBeSafeWhileAnotherThreadLogs() throws Exception {
        Logger logger = Context.getCurrentLogger();
        ch.qos.logback.classic.Logger backend =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logger.getName());
        boolean additive = backend.isAdditive();
        // Keep the flood of background lines out of the console and the test report.
        backend.setAdditive(false);
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        try (LogCapture logs = new LogCapture()) {
            Thread writer = new Thread(() -> {
                started.countDown();
                while (running.get()) {
                    logger.warning("background");
                }
            }, "log-capture-writer");
            writer.setDaemon(true);
            writer.start();
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                assertDoesNotThrow(() -> {
                    while (System.nanoTime() < deadline) {
                        logs.messages();
                        logs.events().stream().filter(e -> e.getThrowableProxy() != null)
                                .count();
                    }
                });
                // Without this, the test would also pass if the writer never logged.
                assertTrue(logs.messages().contains("background"),
                        "the background thread never logged, so nothing was tested");
            } finally {
                running.set(false);
                writer.join(5_000);
            }
        } finally {
            backend.setAdditive(additive);
        }
    }

    private static List<String> formatted(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
