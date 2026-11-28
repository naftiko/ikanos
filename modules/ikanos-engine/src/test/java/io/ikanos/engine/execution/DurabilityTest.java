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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import io.ikanos.durable.ExecutionId;
import io.ikanos.durable.ExecutionRequest;
import io.ikanos.durable.store.ExecutionStatus;
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

    private static DurabilitySpec local(String type, String path) {
        DurabilitySpec spec = new DurabilitySpec("local");
        DurabilityStoreSpec store = new DurabilityStoreSpec(type);
        store.setPath(path);
        spec.setStore(store);
        return spec;
    }

    @Test
    void fromSpecShouldOpenTheH2StoreForTheLocalModeAndKeepExecutionsAcrossARestart(
            @TempDir Path directory) {
        String path = directory.resolve("durable").toString();
        ExecutionId id = new ExecutionId("exec-1");

        try (Durability first = Durability.fromSpec(local(null, path))) {
            assertTrue(first.isDurable());
            assertEquals("local", first.mode());
            assertTrue(first.runner().create(ExecutionRequest.of(id, null), "mcp:ops/clear"));
        }
        try (Durability second = Durability.fromSpec(local("h2", path))) {
            assertEquals(ExecutionStatus.RUNNING, second.runner().status(id).orElseThrow());
            assertFalse(second.runner().create(ExecutionRequest.of(id, null), "mcp:ops/clear"));
        }
    }

    @Test
    void fromSpecShouldRequireAPathForTheLocalMode() {
        assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(new DurabilitySpec("local")));
        assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(local("h2", " ")));
    }

    @Test
    void fromSpecShouldRejectAnotherStoreForTheLocalMode(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> Durability.fromSpec(
                local("memory", directory.resolve("durable").toString())));
    }

    @Test
    void closeShouldBeHarmlessWithoutAStore() {
        Durability.none().close();
        Durability.fromSpec(new DurabilitySpec("memory")).close();
    }

    @Test
    void fromSpecShouldRejectAnUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(new DurabilitySpec("forever")));
    }

    private static DurabilitySpec distributed(String type, String url) {
        DurabilitySpec spec = new DurabilitySpec("distributed");
        DurabilityStoreSpec store = new DurabilityStoreSpec(type);
        store.setUrl(url);
        spec.setStore(store);
        // A test key of 128 zero bits, made here rather than written as a key-like literal.
        spec.setSealingKey(java.util.Base64.getEncoder().encodeToString(new byte[16]));
        return spec;
    }

    @Test
    void fromSpecShouldRequireASealingKeyForTheDistributedMode() {
        DurabilitySpec spec = distributed("postgres", "jdbc:postgresql://db/ikanos");
        spec.setSealingKey(" ");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(spec));

        assertTrue(e.getMessage().contains("durability.sealingKey"), e.getMessage());
    }

    @Test
    void fromSpecShouldRequireAUrlForTheDistributedMode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(new DurabilitySpec("distributed")));

        assertTrue(e.getMessage().contains("durability.store.url"), e.getMessage());
    }

    @Test
    void fromSpecShouldRefuseAStoreOfOneProcessForTheDistributedMode() {
        for (String type : new String[] {"h2", "memory"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Durability.fromSpec(distributed(type, "jdbc:postgresql://db/ikanos")));
            assertTrue(e.getMessage().contains("several replicas share"), e.getMessage());
        }
    }

    @Test
    void fromSpecShouldNameTheStoreSettingThatDoesNotResolve() {
        DurabilitySpec spec = distributed(null, "{{DURABLE_DB_URL}}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(spec, "sha256:tree", java.util.Map.of()));

        assertTrue(e.getMessage().contains("durability.store.url does not resolve"),
                e.getMessage());
    }

    @Test
    void settingShouldResolveABindingAndKeepALiteral() {
        java.util.Map<String, Object> bindings =
                java.util.Map.of("DURABLE_DB_PASSWORD", "from-binds");

        assertEquals("from-binds",
                Durability.setting("{{DURABLE_DB_PASSWORD}}", "password", bindings));
        assertEquals("ikanos", Durability.setting("ikanos", "user", bindings));
        assertNull(Durability.setting(" ", "user", bindings));
    }

    @Test
    void fromSpecShouldFailTheLoadWhenTheSharedDatabaseIsUnreachable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Durability.fromSpec(distributed("postgres",
                        "jdbc:postgresql://127.0.0.1:1/ikanos")));

        assertTrue(e.getMessage().contains("PostgreSQL store"), e.getMessage());
    }

    @Test
    void codecShouldRoundTripJson() {
        JsonPayloadCodec codec = new JsonPayloadCodec();
        JsonNode node = (JsonNode) codec.decode(codec.encode(
                java.util.Map.of("voyageId", "VOY-2026-042")));
        assertEquals("VOY-2026-042", node.path("voyageId").asString());
    }

    private static DurabilitySpec local(String path) {
        DurabilitySpec spec = new DurabilitySpec("local");
        DurabilityStoreSpec store = new DurabilityStoreSpec(null);
        store.setPath(path);
        spec.setStore(store);
        return spec;
    }

    private static java.util.function.Function<String, String> environment(String store,
            String replicas) {
        java.util.Map<String, String> variables = new java.util.HashMap<>();
        variables.put(Durability.STORE_VARIABLE, store);
        variables.put(Durability.REPLICAS_VARIABLE, replicas);
        return variables::get;
    }

    @Test
    void placementShouldBeUnmanagedWithoutTheVariables() {
        assertEquals(Durability.Placement.NONE,
                Durability.Placement.fromEnvironment(environment(null, " ")));
    }

    @Test
    void placementShouldReadTheStoreAndTheReplicas() {
        assertEquals(new Durability.Placement("postgres", 3),
                Durability.Placement.fromEnvironment(environment("postgres", "3")));
    }

    @Test
    void placementShouldRefuseAValueThatDoesNotParse() {
        for (String replicas : new String[] {"0", "-2", "three", "2.5"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Durability.Placement.fromEnvironment(environment(null, replicas)));
            assertTrue(e.getMessage().contains(Durability.REPLICAS_VARIABLE), e.getMessage());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.Placement.fromEnvironment(environment("redis", "1")));
        assertTrue(e.getMessage().contains(Durability.STORE_VARIABLE), e.getMessage());
    }

    @Test
    void checkPlacementShouldCheckNothingWithoutAnOperator() {
        Durability.checkPlacement(local("tasks.db"), Durability.Placement.NONE);
        Durability.checkPlacement(new DurabilitySpec("memory"), null);
    }

    @Test
    void checkPlacementShouldRefuseAModeOfOneProcessOnSeveralReplicas() {
        for (DurabilitySpec spec : new DurabilitySpec[] {local("tasks.db"),
                new DurabilitySpec("memory")}) {
            String store = "local".equals(spec.effectiveMode()) ? "h2" : null;
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Durability.checkPlacement(spec, new Durability.Placement(store, 3)));
            assertTrue(e.getMessage().contains("cannot run as 3 replicas"), e.getMessage());
            assertTrue(e.getMessage().contains("spec.durability.replicas"), e.getMessage());
        }
    }

    @Test
    void checkPlacementShouldLetModesWithoutAStoreScale() {
        Durability.checkPlacement(null, new Durability.Placement(null, 5));
        Durability.checkPlacement(new DurabilitySpec("none"), new Durability.Placement(null, 5));
        Durability.checkPlacement(new DurabilitySpec("memory"),
                new Durability.Placement(null, 1));
    }

    @Test
    void checkPlacementShouldAcceptTheStoreTheModeNeeds() {
        Durability.checkPlacement(local("tasks.db"), new Durability.Placement("h2", 1));
        Durability.checkPlacement(distributed(null, "jdbc:postgresql://db/ikanos"),
                new Durability.Placement("postgres", 3));
    }

    @Test
    void checkPlacementShouldRefuseAStoreForAModeWithoutOne() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.checkPlacement(new DurabilitySpec("memory"),
                        new Durability.Placement("h2", 1)));

        assertTrue(e.getMessage().contains("remove spec.durability.store"), e.getMessage());
        assertTrue(e.getMessage().contains("Declare durability mode 'local' with store type 'h2'"),
                e.getMessage());
    }

    @Test
    void checkPlacementShouldRefuseAnotherStoreThanTheModeNeeds() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.checkPlacement(local("tasks.db"),
                        new Durability.Placement("postgres", 1)));

        assertTrue(e.getMessage().contains("store 'h2'"), e.getMessage());
        assertTrue(e.getMessage().contains("with store 'postgres'"), e.getMessage());
        assertTrue(e.getMessage().contains("Set spec.durability.store to 'h2'"), e.getMessage());
        assertTrue(e.getMessage().contains(
                "declare durability mode 'distributed' with store type 'postgres'"),
                e.getMessage());
    }

    @Test
    void checkPlacementShouldLeaveAStoreTheModeCannotUseToTheLoad() {
        DurabilitySpec memoryOnH2 = new DurabilitySpec("memory");
        memoryOnH2.setStore(new DurabilityStoreSpec("h2"));
        DurabilitySpec localOnPostgres = local("tasks.db");
        localOnPostgres.getStore().setType("postgres");
        DurabilitySpec[] specs = {distributed("h2", "jdbc:postgresql://db/ikanos"),
                distributed("memory", "jdbc:postgresql://db/ikanos"), memoryOnH2,
                localOnPostgres};

        for (DurabilitySpec spec : specs) {
            Durability.checkPlacement(spec, new Durability.Placement("postgres", 3));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.fromSpec(specs[0]));
        assertTrue(e.getMessage().contains("several replicas share"), e.getMessage());
    }

    @Test
    void checkPlacementShouldRefuseADurableModeTheOperatorGaveNoStore() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Durability.checkPlacement(local("tasks.db"),
                        new Durability.Placement(null, 1)));

        assertTrue(e.getMessage().contains("without a store"), e.getMessage());
    }

    @Test
    void checkPlacementShouldLeaveAnUnknownModeToTheLoad() {
        Durability.checkPlacement(new DurabilitySpec("forever"),
                new Durability.Placement("h2", 3));
    }
}
