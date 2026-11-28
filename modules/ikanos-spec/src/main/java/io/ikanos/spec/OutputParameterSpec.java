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
package io.ikanos.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import io.ikanos.spec.util.StructureSpec;

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

    /**
     * Every key declared beside {@code schema:} (other than {@code name} and {@code $ref}),
     * with its value as declared. The deserializer drops a key it does not model (such as
     * {@code format}) and a value it ignores (such as an {@code enum} that is not a list), so
     * the definitions expander could not reject them otherwise: an output takes no other key
     * beside {@code schema:}. The serializer writes them back, so a copy is still rejected.
     */
    private final Map<String, JsonNode> schemaSiblings = new LinkedHashMap<>();

    /**
     * The binding of a schema reference in a mapped (call-mode) output (#1036): each key is a
     * path in the schema (property names joined by {@code .}, {@code []} for an array's items),
     * each value the {@code mapping} that node carries once expanded. Empty when the output
     * declares none.
     */
    private final Map<String, String> mappings = new LinkedHashMap<>();

    /**
     * The mapping sets of {@code definitions} that bind a schema reference in a mapped
     * (call-mode) output, in order (#1133). Their mappings merge by path: a later set wins, and
     * {@link #getMappings()} wins last. Empty when the output lists none.
     */
    private final List<String> mappingSets = new ArrayList<>();

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

    /**
     * @return the keys declared beside {@code schema:}, other than {@code name} and
     *         {@code $ref}, with their values as declared, in declaration order; empty when
     *         there are none
     */
    public Map<String, JsonNode> getSchemaSiblings() {
        return schemaSiblings;
    }

    /**
     * @return the paths of a referenced schema and their mappings, in declaration order; empty
     *         when the output declares no {@code mappings}
     */
    public Map<String, String> getMappings() {
        return mappings;
    }

    /**
     * @return the names of the mapping sets that bind a referenced schema, in order; empty
     *         when the output lists none
     */
    public List<String> getMappingSets() {
        return mappingSets;
    }
}
