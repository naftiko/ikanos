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
package io.ikanos.spec.consumes.grpc;

import java.util.concurrent.atomic.AtomicReference;
import io.ikanos.spec.consumes.ContractOperationSpec;

/** One unary method of a consumed gRPC service. */
public class GrpcProcedureSpec extends ContractOperationSpec {

    private final AtomicReference<String> rpc = new AtomicReference<>();

    /** @return the method name in the adapter's service */
    public String getRpc() {
        return rpc.get();
    }

    public void setRpc(String rpc) {
        this.rpc.set(rpc);
    }
}
