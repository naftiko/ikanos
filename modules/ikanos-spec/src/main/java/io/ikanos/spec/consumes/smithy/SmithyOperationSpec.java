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
package io.ikanos.spec.consumes.smithy;

import java.util.concurrent.atomic.AtomicReference;
import io.ikanos.spec.consumes.ContractOperationSpec;

/** One operation of a consumed Smithy service. */
public class SmithyOperationSpec extends ContractOperationSpec {

    private final AtomicReference<String> operation = new AtomicReference<>();

    /** @return the operation shape name in the service, or a full shape ID */
    public String getOperation() {
        return operation.get();
    }

    public void setOperation(String operation) {
        this.operation.set(operation);
    }
}
