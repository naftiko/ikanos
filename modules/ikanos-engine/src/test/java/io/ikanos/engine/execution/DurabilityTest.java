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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import io.ikanos.spec.exposes.control.DurabilitySpec;
import io.ikanos.spec.exposes.control.DurabilityStoreSpec;

public class DurabilityTest {

    @Test
    void fromSpecShouldResolveNoBlockToNone() {
        Durability none = Durability.fromSpec(null);
        assertSame(Durability.none(), none);
        assertFalse(none.isDurable());
        assertNull(none.runner());
        assertEquals("none", none.mode());
    }

    @Test
    void fromSpecShouldOpenTheMemoryStoreForTheMemoryMode() {
        Durability memory = Durability.fromSpec(new DurabilitySpec("memory"));
        assertTrue(memory.isDurable());
        assertNotNull(memory.runner());
        assertEquals("memory", memory.mode());
    }

    @Test
    void fromSpecShouldRejectAnotherStoreForTheMemoryMode() {
        DurabilitySpec spec = new DurabilitySpec("memory");
        spec.setStore(new DurabilityStoreSpec("jdbc"));
        assertThrows(IllegalArgumentException.class, () -> Durability.fromSpec(spec));
    }

    @Test
    void fromSpecShouldFailForTheLocalModeUntilItsStoreShips() {
        assertThrows(IllegalStateException.class,
                () -> Durability.fromSpec(new DurabilitySpec("local")));
    }

    @Test
    void fromSpecShouldRejectAnUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(new DurabilitySpec("forever")));
        assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(new DurabilitySpec("distributed")));
    }

    @Test
    void codecShouldRoundTripJson() {
        JsonPayloadCodec codec = new JsonPayloadCodec();
        JsonNode node = (JsonNode) codec.decode(codec.encode(
                java.util.Map.of("voyageId", "VOY-2026-042")));
        assertEquals("VOY-2026-042", node.path("voyageId").asString());
    }
}
