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
package io.ikanos.spec.util;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * An {@code elicit} step: asks the caller of the current invocation for input, through its
 * protocol, and continues with the answer as the step's output.
 *
 * <p>Over MCP, the question becomes an {@code elicitation/create} input request. Under the
 * {@code none} durability mode it uses a stateless multi round-trip request, which replays the
 * flow from the start with the answers collected so far; that is only safe when every step
 * before it is read-only.</p>
 *
 * <p>In {@link #URL} mode the caller is sent to a page to complete a step out of band instead
 * of answering {@code fields}. The caller's {@code accept} only means consent: on each retry,
 * {@link #getCheck() check} decides whether the step is done, and the caller is asked again until
 * it is.</p>
 */
public class OperationStepElicitSpec extends OperationStepSpec {

    /** The mode of a step that asks for {@code fields}, the default. */
    public static final String FORM = "form";

    /** The mode of a step that sends the caller to a page. */
    public static final String URL = "url";

    private volatile String message;
    private volatile Map<String, Object> fields;
    private volatile String mode;
    private volatile String url;
    private volatile OperationStepCallSpec check;
    private volatile Map<String, Object> outputParameters;

    public OperationStepElicitSpec() {
        this(null, null, null);
    }

    public OperationStepElicitSpec(String name, String message, Map<String, Object> fields) {
        super("elicit", name);
        this.message = message;
        this.fields = fields != null ? new LinkedHashMap<>(fields) : null;
    }

    /**
     * The question shown to the caller; the step name when absent. A Mustache template, rendered
     * when the question is asked against the inputs and the outputs of the steps that ran before
     * it; a variable that nothing provides fails the step.
     */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * The fields asked for, by name: each value is a JSON Schema property (a primitive
     * {@code type}, optionally a {@code description}; a string field may also declare an
     * {@code enum} of strings or a {@code format} among email, uri, date, date-time). Every
     * field is required.
     */
    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields != null ? new LinkedHashMap<>(fields) : null;
    }

    /** {@link #FORM} or {@link #URL}; {@code null} when not declared, which means form. */
    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** Whether the step sends the caller to a page ({@link #URL} mode). */
    @JsonIgnore
    public boolean isUrlMode() {
        return URL.equals(mode);
    }

    /**
     * URL mode: the page the caller opens, a Mustache template rendered like the message, which
     * may also read {@code {{elicitation.id}}}.
     */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * URL mode: the call that decides on each retry whether the out-of-band step is done. Its
     * {@code with} may read {@code {{elicitation.id}}}, and should carry the caller's subject
     * ({@code {{auth.claims.sub}}}) so the upstream returns nothing when another user completed
     * the interaction under that id: anyone who has the URL can complete it.
     */
    public OperationStepCallSpec getCheck() {
        return check;
    }

    public void setCheck(OperationStepCallSpec check) {
        this.check = check;
    }

    /**
     * URL mode: the values the step outputs, by name, each a JSON Schema property, read from the
     * top level of the check's output. The step is done once every one of them has a value.
     */
    public Map<String, Object> getOutputParameters() {
        return outputParameters;
    }

    public void setOutputParameters(Map<String, Object> outputParameters) {
        this.outputParameters = outputParameters != null
                ? new LinkedHashMap<>(outputParameters) : null;
    }
}
