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
package io.ikanos.durable.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.ikanos.durable.ActionExecutor;
import io.ikanos.durable.ActionOutcome;
import io.ikanos.durable.Command;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.ExecutionResult;
import io.ikanos.durable.Interpreter;
import io.ikanos.durable.NodePath;
import io.ikanos.durable.store.ExecutionStatus;
import io.ikanos.durable.store.FaultInjectingStores;
import io.ikanos.durable.store.FaultInjectingStores.Boundary;
import io.ikanos.durable.store.JournalEntry;
import io.ikanos.durable.store.PayloadCodec;
import io.ikanos.durable.store.Stores;
import io.ikanos.durable.store.memory.MemoryStoreProvider;

/**
 * Crash injection at every journal boundary of a two-action execution (screen, then book): after
 * a crash and a restart on the same stores, the execution completes with the result of an
 * uninterrupted run, every action is effective, and only the action the crash cut short during
 * its call runs a second time, with the same idempotency key.
 */
public class JournaledRunnerCrashTest {

    private static final ExecutionId ID = new ExecutionId("task-1");
    private static final NodePath SCREEN = new NodePath("steps/screen");
    private static final NodePath BOOK = new NodePath("steps/book-slot");

    private static final PayloadCodec STRINGS = new PayloadCodec() {
        @Override
        public byte[] encode(Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object decode(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    };

    private static final Interpreter SCREEN_THEN_BOOK = view -> {
        for (NodePath path : List.of(SCREEN, BOOK)) {
            if (view.outcome(path).isEmpty()) {
                return List.of(new Command.ScheduleAction(path, path.value(), null));
            }
        }
        return List.of(new Command.Complete(
                ((ActionOutcome.Succeeded) view.outcome(BOOK).orElseThrow()).value()));
    };

    @ParameterizedTest(name = "{0} #{1}")
    @CsvSource({
        "AFTER_SCHEDULED, 1, 1, 1",
        "AFTER_SCHEDULED, 2, 1, 1",
        "BEFORE_OUTCOME, 1, 2, 1",
        "BEFORE_OUTCOME, 2, 1, 2",
        "AFTER_OUTCOME, 1, 1, 1",
        "AFTER_OUTCOME, 2, 1, 1",
        "AFTER_COMPLETED, 1, 1, 1"})
    void aRestartShouldCompleteTheExecutionAsIfNothingHappened(Boundary boundary,
            int occurrence, int screenCalls, int bookCalls) {
        Stores stores = MemoryStoreProvider.openStores(Clock.systemUTC());
        Map<NodePath, Integer> calls = new ConcurrentHashMap<>();
        ActionExecutor upstream = request -> {
            calls.merge(request.path(), 1, Integer::sum);
            return "result of " + request.action();
        };

        JournaledRunner beforeCrash = new JournaledRunner(
                FaultInjectingStores.wrap(stores, boundary, occurrence), STRINGS,
                Clock.systemUTC(), "sha256:tree");
        assertThrows(FaultInjectingStores.Crash.class,
                () -> beforeCrash.run(ExecutionRequest.of(ID, null), SCREEN_THEN_BOOK, upstream));

        JournaledRunner afterRestart =
                new JournaledRunner(stores, STRINGS, Clock.systemUTC(), "sha256:tree");
        ExecutionResult result =
                afterRestart.run(ExecutionRequest.of(ID, null), SCREEN_THEN_BOOK, upstream);

        assertEquals(new ExecutionResult.Completed("result of steps/book-slot"), result);
        assertEquals(ExecutionStatus.COMPLETED, afterRestart.status(ID).orElseThrow(),
                "The index catches up with the journal");
        assertEquals(screenCalls, calls.get(SCREEN), "screen calls");
        assertEquals(bookCalls, calls.get(BOOK), "book-slot calls");

        List<String> keys = stores.journal().read(ID).stream()
                .filter(e -> e.kind() == JournalEntry.Kind.ACTION_SCHEDULED && BOOK.equals(e.path()))
                .map(e -> new String(e.payload(), StandardCharsets.UTF_8)).distinct().toList();
        assertEquals(List.of(JournaledRunner.idempotencyKey(ID, BOOK)), keys,
                "Every attempt at the booking carries the same idempotency key");
    }
}
