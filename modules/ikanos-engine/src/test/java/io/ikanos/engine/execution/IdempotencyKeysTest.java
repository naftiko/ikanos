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
package io.ikanos.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.core.JournaledRunner;
import io.ikanos.durable.store.memory.MemoryStoreProvider;

public class IdempotencyKeysTest {

    private final JournaledRunner runner = new JournaledRunner(
            MemoryStoreProvider.openStores(Clock.systemUTC()), new JsonPayloadCodec(),
            Clock.systemUTC(), "test");

    @Test
    void nameBasedShouldMatchTheVersion5UuidOfRfc9562() {
        // Reference values from Python's uuid.uuid5(uuid.NAMESPACE_URL, name).
        assertEquals(UUID.fromString("7af94e2b-4dd9-50f0-9c9a-8a48519bdef0"),
                IdempotencyKeys.nameBased("python.org"));
        UUID key = IdempotencyKeys.nameBased("urn:ikanos:action:task-1/steps/book-slot");
        assertEquals(UUID.fromString("17711fc2-4c03-570d-add0-33c4cc467df3"), key);
        assertEquals(5, key.version());
    }

    @Test
    void aCallOutsideAJournalShouldDrawARandomKeyEachTime() {
        UUID first = UUID.fromString(IdempotencyKeys.forCurrentCall());
        UUID second = UUID.fromString(IdempotencyKeys.forCurrentCall());

        assertEquals(4, first.version());
        assertNotEquals(first, second);
    }

    @Test
    void aCallInsideAJournaledRunShouldNameItsActionTheSameWayEachTime() throws Exception {
        ExecutionId task = new ExecutionId("task-1");

        String first = DurableScope.with(runner, task, IdempotencyKeys::forCurrentCall);
        String second = DurableScope.with(runner, task, IdempotencyKeys::forCurrentCall);

        assertEquals("7cc1e255-8062-595c-b511-f0954439a9d4", first);
        assertEquals(first, second, "Every attempt of the same action sends the same key");
        assertNotEquals(first, DurableScope.with(runner, new ExecutionId("task-2"),
                IdempotencyKeys::forCurrentCall));
    }
}
