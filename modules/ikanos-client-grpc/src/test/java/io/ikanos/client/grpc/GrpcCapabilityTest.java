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
package io.ikanos.client.grpc;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/** A capability that consumes gRPC, loaded through the engine. */
class GrpcCapabilityTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @TempDir
    Path dir;

    private Capability load(String hints) throws Exception {
        new OrdersFixture().writeDescriptorSet(dir);
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: grpc
                      namespace: orders
                      endpoint: "http://localhost:50051"
                      descriptorSet: ./orders.binpb
                      service: test.orders.OrderService
                      procedures:
                        cancel-order:
                          description: Cancel an order.
                          rpc: CancelOrder
                          inputParameters:
                            order-id: { type: string, required: true, description: Order id., wireName: id }
                  exposes:
                    - type: mcp
                      namespace: orders-mcp
                      port: 3001
                      %s
                      tools:
                        cancel-order:
                          description: Cancel an order.
                          call: orders.cancel-order
                          inputParameters:
                            order-id: { type: string, required: true, description: Order id. }
                """.formatted(IKANOS, hints);
        IkanosSpec spec = IkanosMappers.yaml().readValue(yaml, IkanosSpec.class);
        return new Capability(spec, dir.toString());
    }

    @Test
    void capabilityShouldLoadTheGrpcAdapterFromItsModule() throws Exception {
        Capability capability = load("");

        assertInstanceOf(GrpcConsumedAdapter.class, capability.getClientAdapters().get(0),
                "found through ServiceLoader; the descriptor set read from the capability directory");
    }

    @Test
    void capabilityShouldRefuseAReadOnlyToolThatReachesAWriteRpc() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("hints: { readOnly: true }"));

        assertTrue(e.getMessage().contains("orders.cancel-order"), e.getMessage());
    }
}
