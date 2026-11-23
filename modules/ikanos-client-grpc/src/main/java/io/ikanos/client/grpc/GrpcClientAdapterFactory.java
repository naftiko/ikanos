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

import java.util.Map;
import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import io.ikanos.engine.consumes.ClientAdapterFactory;
import io.ikanos.engine.consumes.tunnel.Tunnel;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.grpc.GrpcClientSpec;

/** Creates the {@code consumes: type: grpc} adapter; found with {@code ServiceLoader}. */
public class GrpcClientAdapterFactory implements ClientAdapterFactory {

    @Override
    public String type() {
        return GrpcClientSpec.TYPE;
    }

    @Override
    public ClientAdapter create(Capability capability, ClientSpec spec,
            Map<String, Tunnel> tunnels) {
        return new GrpcConsumedAdapter(capability, (GrpcClientSpec) spec, tunnels);
    }
}
