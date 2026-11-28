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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import io.ikanos.spec.util.StructureSpec;

/**
 * Input Parameter Specification Element
 */
@JsonDeserialize(using = InputParameterDeserializer.class)
@JsonSerialize(using = InputParameterSerializer.class)
public class InputParameterSpec extends StructureSpec<InputParameterSpec> {

    /**
     * Type of an MCP tool input ({@code McpToolInputParameter}) that declares none: MCP tool
     * inputs, aggregate flow inputs and consumed MCP tool arguments (#986).
     */
    public static final String DEFAULT_MCP_TOOL_INPUT_TYPE = "string";

    /** Type of a file input (experimental, draft MCP files extension). */
    public static final String FILE_TYPE = "file";

    private volatile String in;

    private volatile String template;

    /**
     * Whether this parameter is required, or {@code null} when the parameter does not say.
     * {@code McpToolInputParameter} (MCP tool inputs, consumed MCP tool arguments and aggregate
     * flow inputs) declares it, and there it defaults to {@code false} (#977).
     * {@code ConsumedInputParameter} (consumed HTTP inputs) declares it too, but only a
     * precondition header input ({@code If-Match}, {@code If-None-Match},
     * {@code If-Unmodified-Since}, {@code If-Range}) of a request that is not safe reads it, and
     * there it defaults to {@code true} (#1001). Readers pick their own default with
     * {@link #isRequiredOrDefault(boolean)}.
     */
    private volatile Boolean required;

    /**
     * Value of the parameter. Supports Mustache template syntax ({{paramName}}) for dynamic
     * resolution from the execution context. Provides an alternative to 'const' that allows
     * parameter variable substitution. Takes precedence over 'const'.
     */
    private volatile String value;

    /**
     * Name sent on the wire when it differs from the map key, for example {@code status[]} for
     * APIs that read list filters with the Rails/PHP {@code name[]} convention (#851). The key
     * stays the parameter name used in templates and {@code with:}.
     */
    private volatile String wireName;

    /**
     * SQL procedures only: the {@code java.sql.JDBCType} name the value is bound as, such as
     * {@code TIMESTAMP} or {@code DECIMAL}.
     */
    private volatile String sqlType;

    /**
     * SQL stored procedures only: the 1-based position of this argument in the call.
     */
    private volatile Integer position;

    /**
     * How a list value is serialized in a query parameter, as in OpenAPI: {@code form} (default),
     * {@code spaceDelimited} or {@code pipeDelimited}.
     */
    private volatile String style;

    /**
     * For {@code form} style: {@code true} (default) repeats the parameter once per element,
     * {@code false} sends one comma-separated value. {@code null} means the default.
     */
    private volatile Boolean explode;

    private volatile String format;

    private volatile Number minimum;

    private volatile Number maximum;

    private volatile Boolean additionalProperties;

    /**
     * MCP tool input {@code default} (#979): the argument value used when a call leaves an
     * optional input out or passes {@code null}, kept as written (any JSON type). {@code null}
     * when none is declared.
     */
    private volatile JsonNode defaultValue;

    /**
     * For a {@code type: file} input: the accepted media types (or ranges such as
     * {@code image/*}) and dot-prefixed file extensions. Empty when the input accepts any file.
     */
    private final List<String> accept = new CopyOnWriteArrayList<>();

    /** For a {@code type: file} input: the largest accepted file, a sized string, or null. */
    private volatile String maxSize;

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

    /**
     * Give every parameter without a {@code type} the MCP tool input default,
     * {@link #DEFAULT_MCP_TOOL_INPUT_TYPE}. Called once, when an MCP tool, an aggregate flow or a
     * consumed MCP tool receives its input parameters, so that every reader of
     * {@link #getType()} (input schema, argument type check, exports) sees the default. Only the
     * top-level parameters are defaulted: a nested {@code items}/{@code properties} schema keeps
     * its own required {@code type}. REST inputs are never passed here. A parameter that
     * references a schema of the definitions ({@code schema: name}) is left untyped: it takes the
     * schema's type when the definitions are expanded, which sets the parameters again.
     *
     * @param params the parameters to update in place, may be {@code null}
     */
    public static void applyMcpToolInputDefaults(Iterable<InputParameterSpec> params) {
        if (params == null) {
            return;
        }
        for (InputParameterSpec param : params) {
            if (param != null && param.getType() == null && param.getSchema() == null) {
                param.setType(DEFAULT_MCP_TOOL_INPUT_TYPE);
            }
        }
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

    /**
     * @return {@code true} only when the parameter declares {@code required: true}: the default of
     *         an MCP tool input is {@code false}, as in JSON Schema
     */
    public boolean isRequired() {
        return isRequiredOrDefault(false);
    }

    /**
     * @param defaultValue the value to use when the parameter does not declare {@code required}
     * @return the declared {@code required} value, or {@code defaultValue}
     */
    public boolean isRequiredOrDefault(boolean defaultValue) {
        Boolean declared = required;
        return declared != null ? declared : defaultValue;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    /**
     * The {@code required} flag as declared, for copies that must keep an undeclared flag
     * undeclared. {@link #getRequired()} is taken by the {@code required} list of an object.
     *
     * @return {@code true} or {@code false} as declared, or {@code null} when not declared
     */
    public Boolean getDeclaredRequired() {
        return required;
    }

    /**
     * @param required the declared {@code required} flag, or {@code null} to leave it undeclared
     */
    public void setDeclaredRequired(Boolean required) {
        this.required = required;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getWireName() {
        return wireName;
    }

    public void setWireName(String wireName) {
        this.wireName = wireName;
    }

    public String getSqlType() {
        return sqlType;
    }

    public void setSqlType(String sqlType) {
        this.sqlType = sqlType;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public Boolean getExplode() {
        return explode;
    }

    public void setExplode(Boolean explode) {
        this.explode = explode;
    }

    /** @return the JSON Schema {@code format} hint advertised to MCP clients, or {@code null} */
    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Number getMinimum() {
        return minimum;
    }

    public void setMinimum(Number minimum) {
        this.minimum = minimum;
    }

    public Number getMaximum() {
        return maximum;
    }

    public void setMaximum(Number maximum) {
        this.maximum = maximum;
    }

    public Boolean getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(Boolean additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    /** @return the accepted media types and extensions of a file input, never null */
    public List<String> getAccept() {
        return accept;
    }

    /** @return the largest accepted file of a file input, a sized string, or {@code null} */
    public String getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(String maxSize) {
        this.maxSize = maxSize;
    }

    /** @return whether this input takes a file ({@code type: file}) */
    public boolean isFile() {
        return FILE_TYPE.equals(getType());
    }

    /** @return the declared {@code default}, or {@code null} when none is declared */
    public JsonNode getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(JsonNode defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * The {@code enum} values with the parameter's JSON type: they are stored as strings, and a
     * schema that lists {@code "1"} for an {@code integer} parameter accepts no value. Used by
     * every exporter of an input schema (MCP {@code tools/list}, OpenAPI).
     *
     * @return the values, as {@link BigInteger} for {@code integer}, {@link BigDecimal} for
     *         {@code number}, {@link Boolean} for {@code boolean}, and as written otherwise or
     *         when a value does not parse
     */
    public List<Object> typedEnumeration() {
        String type = getType() != null ? getType() : "string";
        List<Object> values = new ArrayList<>();
        for (String value : getEnumeration()) {
            values.add(typedEnumValue(type, value));
        }
        return values;
    }

    /**
     * Whether a query value of this parameter is a JSON document: an {@code object}, or an
     * {@code array} of objects, has no form encoding that the REST adapter reads, so it is sent
     * as JSON in one query parameter ({@code ?filter={"flag":"NO"}}) and the OpenAPI export
     * declares it with {@code content: application/json}.
     *
     * @return {@code true} for an object or an array of objects
     */
    public boolean isJsonInQuery() {
        return "object".equalsIgnoreCase(getType()) || ("array".equalsIgnoreCase(getType())
                && getItems() != null && "object".equalsIgnoreCase(getItems().getType()));
    }

    /**
     * Read one {@code enum} value, stored as a string, with a parameter's JSON type. A value that
     * does not parse as the type, such as {@code "50.0"} or {@code "1e2"} on an {@code integer}
     * parameter, stays a string.
     *
     * @param type the parameter type, may be null (read as {@code string})
     * @param value the enum value as written
     * @return a {@link BigInteger}, {@link BigDecimal}, {@link Boolean} or the string itself
     */
    public static Object typedEnumValue(String type, String value) {
        if (type == null) {
            return value;
        }
        try {
            switch (type) {
                case "integer":
                    return new BigInteger(value);
                case "number":
                    return new BigDecimal(value);
                case "boolean":
                    if ("true".equals(value) || "false".equals(value)) {
                        return Boolean.valueOf(value);
                    }
                    return value;
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * Deep copy of this declaration, so that the copy can be changed without changing the
     * original: every use of a shared definition owns its copy, and a REST operation places the
     * inputs it inherits from its flow without changing the flow.
     *
     * @return the copy, with copies of its {@code items}, {@code values} and properties
     */
    public InputParameterSpec copy() {
        InputParameterSpec copy = copyNode();
        copy.setItems(getItems() != null ? getItems().copy() : null);
        copy.setValues(getValues() != null ? getValues().copy() : null);
        for (InputParameterSpec property : getProperties()) {
            copy.getProperties().add(property.copy());
        }
        return copy;
    }

    /**
     * Copy of this declaration without its {@code items}, {@code values} and properties.
     *
     * @return the copy
     */
    public InputParameterSpec copyNode() {
        InputParameterSpec copy = new InputParameterSpec();
        copy.setName(getName());
        copy.setType(getType());
        copy.setDescription(getDescription());
        copy.setSchema(getSchema());
        copy.setJsonSchemaRef(getJsonSchemaRef());
        copy.setIn(getIn());
        copy.setTemplate(getTemplate());
        copy.setDeclaredRequired(getDeclaredRequired());
        copy.setValue(getValue());
        copy.setWireName(getWireName());
        copy.setStyle(getStyle());
        copy.setExplode(getExplode());
        copy.setFormat(getFormat());
        copy.setMinimum(getMinimum());
        copy.setMaximum(getMaximum());
        copy.setAdditionalProperties(getAdditionalProperties());
        copy.setConstant(getConstant());
        copy.setSelector(getSelector());
        copy.setMaxLength(getMaxLength());
        copy.setPrecision(getPrecision());
        copy.setScale(getScale());
        copy.setContentEncoding(getContentEncoding());
        copy.setContentCompression(getContentCompression());
        copy.setContentMediaType(getContentMediaType());
        copy.setDefaultValue(getDefaultValue() != null ? getDefaultValue().deepCopy() : null);
        copy.getRequired().addAll(getRequired());
        copy.getEnumeration().addAll(getEnumeration());
        copy.getExamples().addAll(getExamples());
        copy.getTuple().addAll(getTuple());
        copy.getAccept().addAll(getAccept());
        copy.setMaxSize(getMaxSize());
        return copy;
    }

}
