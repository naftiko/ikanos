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
package io.ikanos.spec.consumes;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OperationSpec;

/**
 * One operation of a contract-described consumed adapter ({@code grpc} procedure,
 * {@code graphql} or {@code smithy} operation).
 *
 * <p>Besides the fields of {@link OperationSpec} ({@code title}, {@code tags},
 * {@code description}, typed {@code inputParameters} whose {@code wireName} is a dotted path into
 * the request, {@code outputParameters}), it carries {@code readOnly}, derived from the contract
 * when absent, and {@code timeoutMs}.</p>
 */
public abstract class ContractOperationSpec extends OperationSpec {

    /** Default {@code timeoutMs}, as for SQL procedures. */
    public static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final AtomicReference<Boolean> readOnly = new AtomicReference<>();
    private final AtomicReference<Integer> timeoutMs = new AtomicReference<>();

    /** @return the declared {@code readOnly}, or {@code null} to derive it from the contract */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getReadOnly() {
        return readOnly.get();
    }

    public void setReadOnly(Boolean readOnly) {
        this.readOnly.set(readOnly);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getTimeoutMs() {
        return timeoutMs.get();
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs.set(timeoutMs);
    }

    /** @return the effective timeout in milliseconds */
    @JsonIgnore
    public int timeoutMillis() {
        Integer value = timeoutMs.get();
        return value != null && value > 0 ? value : DEFAULT_TIMEOUT_MS;
    }

    @Override
    public void setInputParameters(Map<String, InputParameterSpec> params) {
        super.setInputParameters(params);
        InputParameterSpec.applyMcpToolInputDefaults(getInputParameters());
    }
}
