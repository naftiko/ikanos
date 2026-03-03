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
package io.naftiko.spec;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Input Parameter Specification Element
 */
@JsonDeserialize(using = InputParameterDeserializer.class)
@JsonSerialize(using = InputParameterSerializer.class)
public class InputParameterSpec extends StructureSpec<InputParameterSpec> {

    private volatile String in;

    private volatile String template;

    /**
     * Value of the parameter. Supports Mustache template syntax ({{paramName}}) for dynamic
     * resolution from the execution context. Provides an alternative to 'const' that allows
     * parameter variable substitution. Takes precedence over 'const'.
     */
    private volatile String value;

    public InputParameterSpec() {
        super();
    }

    public InputParameterSpec(String name, String type, String in, String template) {
        super(name, type, null, null, null, null, null, null, null, null, null, null, null);
        this.in = in;
        this.template = template;
    }

    public InputParameterSpec(String name, String type, InputParameterSpec items, InputParameterSpec values,
            String constant, String selector, String maxLength, Integer precision, Integer scale,
            String contentEncoding, String contentCompression, String contentMediaType,
            String description, String in, String template) {
        super(name, type, items, values, constant, selector, maxLength, precision, scale,
                contentEncoding, contentCompression, contentMediaType, description);
        this.in = in;
        this.template = template;
    }

    public String getIn() {
        return in;
    }

    public void setIn(String in) {
        this.in = in;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

}
