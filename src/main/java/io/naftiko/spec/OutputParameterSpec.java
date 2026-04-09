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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Output Parameter Specification Element
 */
@JsonDeserialize(using = OutputParameterDeserializer.class)
@JsonSerialize(using = OutputParameterSerializer.class)
public class OutputParameterSpec extends StructureSpec<OutputParameterSpec> {

    private volatile String in;

    @JsonProperty("mapping")
    private volatile String mapping;

    /**
     * Static value for mock responses. When provided, the runtime uses this value
     * as-is instead of resolving via mapping from an external API response.
     */
    private volatile String value;

    public OutputParameterSpec() {
        super();
    }

    public OutputParameterSpec(String name, String type, String in, String mapping) {
        super(name, type, null, null, null, null, null, null, null, null, null, null, null);
        this.in = in;
        this.mapping = mapping;
    }

    public OutputParameterSpec(String name, String type, String mapping, OutputParameterSpec items, OutputParameterSpec values,
            String constant, String selector, String maxLength, Integer precision, Integer scale,
            String contentEncoding, String contentCompression, String contentMediaType,
            String description, String in) {
        super(name, type, items, values, constant, selector, maxLength, precision, scale,
                contentEncoding, contentCompression, contentMediaType, description);
        this.in = in;
        this.mapping = mapping;
    }

    public String getIn() {
        return in;
    }

    public void setIn(String in) {
        this.in = in;
    }

    public String getMapping() {
        return mapping;
    }

    public void setMapping(String mapping) {
        this.mapping = mapping;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

}
