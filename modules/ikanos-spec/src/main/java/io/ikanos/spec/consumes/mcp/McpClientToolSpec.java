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
package io.ikanos.spec.consumes.mcp;

import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ikanos.spec.OperationSpec;
import io.ikanos.spec.exposes.mcp.McpToolHintsSpec;

/**
 * One consumed MCP tool, keyed under {@code consumes[type=mcp].tools} by the upstream tool name.
 *
 * <p>Extends {@link OperationSpec} to reuse {@code name}, {@code description},
 * {@code inputParameters} (map form, each key an upstream argument name) and consumed
 * {@code outputParameters} (JsonPath {@code value} mappings evaluated against the selected
 * response document). The HTTP-only {@code method}, {@code outputRawFormat} and
 * {@code outputSchema} fields are inherited but unused.</p>
 */
public class McpClientToolSpec extends OperationSpec {

    /** {@code validateOutput: fail} — a schema violation fails the call (default). */
    public static final String VALIDATE_FAIL = "fail";

    /** {@code validateOutput: warn} — a schema violation is logged and recorded on the span. */
    public static final String VALIDATE_WARN = "warn";

    /** {@code validateOutput: off} — no validation. */
    public static final String VALIDATE_OFF = "off";

    private final AtomicReference<McpToolHintsSpec> hints = new AtomicReference<>();

    private final AtomicReference<String> validateOutput = new AtomicReference<>(VALIDATE_FAIL);

    public McpClientToolSpec() {
        super();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public McpToolHintsSpec getHints() {
        return hints.get();
    }

    public void setHints(McpToolHintsSpec hints) {
        this.hints.set(hints);
    }

    /** @return {@code fail} (default), {@code warn} or {@code off} */
    public String getValidateOutput() {
        return validateOutput.get();
    }

    public void setValidateOutput(String validateOutput) {
        // A bare YAML `off` arrives as "false" (YAML 1.1 boolean); see McpClientSpec#normalize.
        this.validateOutput.set(McpClientSpec.normalize(validateOutput, VALIDATE_FAIL));
    }

    /** @return the validation mode, normalized to lower case */
    @JsonIgnore
    public String validationMode() {
        String mode = getValidateOutput();
        return mode == null ? VALIDATE_FAIL : mode.toLowerCase(java.util.Locale.ROOT);
    }
}
