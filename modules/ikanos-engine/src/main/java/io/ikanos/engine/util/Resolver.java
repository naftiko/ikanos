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
package io.ikanos.engine.util;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.restlet.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.samskivert.mustache.Mustache;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;

/**
 * Utility class for resolving Mustache-style template strings with provided parameters.
 */
public class Resolver {

    private static final Logger logger = LoggerFactory.getLogger(Resolver.class);

    private Resolver() {
        // Utility class, no instantiation
    }

    /**
     * Resolve Mustache-style templates in a string using provided parameters. Replaces
     * {{paramName}} with the corresponding parameter value from the map using JMustache.
     * 
     * Missing variables will remain as {{paramName}} in the output. Null parameter values are
     * replaced with empty strings.
     * 
     * @param template The template string containing {{...}} placeholders
     * @param parameters Map of parameter names to values for template resolution
     * @return The template string with all placeholders resolved, or the original template if
     *         parameters is null or empty
     */
    public static String resolveMustacheTemplate(String template, Map<String, Object> parameters) {
        return resolveMustacheTemplate(template, parameters, null);
    }

    /**
     * Same as {@link #resolveMustacheTemplate(String, Map)}, with a transformation applied to each
     * string and number value (collections and arrays are JSON-serialized first, so they are
     * transformed too). Other values, such as a {@code Boolean}, are left untouched so that Mustache
     * sections on them are evaluated exactly as without a transformation. Used, for example, to
     * percent-encode values substituted into a pre-encoded form body.
     *
     * @param template the template string containing {{...}} placeholders
     * @param parameters map of parameter names to values for template resolution
     * @param valueEncoder transformation of each substituted value, or {@code null} for none
     * @return the resolved string, or the original template if parameters is null or empty
     */
    public static String resolveMustacheTemplate(String template, Map<String, Object> parameters,
            java.util.function.UnaryOperator<String> valueEncoder) {
        if (template == null) {
            return template;
        }

        if (parameters == null || parameters.isEmpty()) {
            return template;
        }

        // JSON-serialize non-scalar values (arrays, maps) so that Mustache substitution
        // produces valid JSON instead of calling toString() (e.g. [CREW-001, CREW-003]).
        Map<String, Object> serialized = new HashMap<>();
        ObjectMapper jsonMapper = new ObjectMapper();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof java.util.Collection || val instanceof Object[]) {
                try {
                    val = jsonMapper.writeValueAsString(val);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // keep the plain value
                }
            }
            if (valueEncoder != null && (val instanceof String || val instanceof Number)) {
                val = valueEncoder.apply(String.valueOf(val));
            }
            serialized.put(entry.getKey(), val);
        }

        // escapeHTML(false): JMustache escapes HTML entities by default (e.g. " → &quot;).
        // This is desirable for HTML output, but templates here produce JSON bodies or URI strings —
        // never HTML. Without this, serialized array values like ["CREW-001"] would be rendered
        // as [&quot;CREW-001&quot;], producing invalid JSON.
        return Mustache.compiler().escapeHTML(false).defaultValue("").compile(template)
            .execute(serialized);
    }

    /**
     * Resolve a single InputParameterSpec from a request, extracting the value based on the
     * parameter location (path, query, header, environment, or body).
     * 
     * @param spec The input parameter specification
     * @param request The HTTP request
     * @param root The parsed JSON root from the request body (may be null)
     * @param mapper The ObjectMapper for JSON conversion
     * @return The resolved parameter value, or null if not found or cannot be resolved
     */
    public static Object resolveInputParameterFromRequest(InputParameterSpec spec, Request request,
            JsonNode root, ObjectMapper mapper) {
        if (spec == null || spec.getName() == null) {
            return null;
        }

        // value provides a static override when not a JSONPath expression
        if (spec.getValue() != null && !spec.getValue().trim().startsWith("$")) {
            return spec.getValue();
        }

        String in = spec.getIn() == null ? "body" : spec.getIn();
        String name = spec.getName();

        try {
            switch (in.toLowerCase()) {
                case "path": {
                    Object attr = request.getAttributes().get(name);
                    return attr == null ? null : attr;
                }

                case "query": {
                    if (request.getResourceRef() != null
                            && request.getResourceRef().getQueryAsForm() != null) {
                        return request.getResourceRef().getQueryAsForm().getFirstValue(name);
                    }
                    return null;
                }

                case "header": {
                    String hv = request.getHeaders().getFirstValue(name, true);
                    return hv;
                }

                case "environment": {
                    String ev = System.getenv(name);
                    return ev;
                }

                case "body":
                default: {
                    if (root == null) {
                        return null;
                    }

                    // Prefer `value` for JSONPath extraction while keeping `template` backward
                    // compatible for existing specs.
                    String tmpl = spec.getValue() != null ? spec.getValue() : spec.getTemplate();

                    if (tmpl != null && tmpl.trim().startsWith("$")) {
                        JsonNode extracted = Converter.jsonPathExtract(root, tmpl.trim());

                        if (extracted == null || extracted.isNull()) {
                            return null;
                        }

                        if (extracted.isTextual()) {
                            return extracted.asText();
                        } else {
                            return mapper.convertValue(extracted, Object.class);
                        }
                    }

                    // When a field name is given with no explicit path, extract that field
                    // from the body by name so that named body parameters bind to individual
                    // request fields (e.g. name: shipImo → $.shipImo).
                    if (name != null && root.has(name)) {
                        JsonNode field = root.get(name);
                        if (field.isTextual()) {
                            return field.asText();
                        } else if (!field.isNull()) {
                            return mapper.convertValue(field, Object.class);
                        }
                        return null;
                    }

                    // Otherwise return raw body node
                    return root;
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Input parameter resolution failed, returning null", e);
            return null;
        }
    }


    /**
     * Apply a list of input parameter specs to a client request (headers and query params).
     * 
     * Resolution priority for parameter values:
     * 1. 'value' field - resolved with Mustache template syntax ({{paramName}}) for dynamic resolution
     * 2. 'template' field - resolved with Mustache syntax
     * 3. Parameters map - direct lookup by parameter name
     * 4. Environment variables - for 'environment' location
     */
    public static void resolveInputParametersToRequest(Request clientRequest,
            List<InputParameterSpec> specs, Map<String, Object> parameters) {

        if (specs == null || specs.isEmpty() || clientRequest == null) {
            return;
        }

        for (InputParameterSpec spec : specs) {
            try {
                String in = spec.getIn() == null ? "body" : spec.getIn();
                Object val = null;

                if (spec.getValue() != null) {
                    // Resolve Mustache templates in value, allowing dynamic parameter resolution
                    val = Resolver.resolveMustacheTemplate(spec.getValue(), parameters);
                } else if (spec.getTemplate() != null) {
                    val = Resolver.resolveMustacheTemplate(spec.getTemplate(), parameters);
                } else if (parameters != null && parameters.containsKey(spec.getName())) {
                    val = parameters.get(spec.getName());
                } else if ("environment".equalsIgnoreCase(in)) {
                    val = System.getenv(spec.getName());
                }

                if (val == null) {
                    continue;
                } else if (val instanceof String && ((String) val).contains("{{")) {
                    // Value still contains unresolved Mustache placeholders — the parameter
                    // was not actually provided by the caller. Omit it from the request so
                    // that optional parameters are not sent with their raw template text.
                    continue;
                } else if (parameters != null) {
                    parameters.put(spec.getName(), val);
                }

                if ("header".equalsIgnoreCase(in)) {
                    clientRequest.getHeaders().set(spec.getName(), val.toString());
                } else if ("query".equalsIgnoreCase(in)) {
                    String old = clientRequest.getResourceRef().toString();
                    String separator = old.contains("?") ? "&" : "?";
                    String newRef = old + separator + spec.getName() + "="
                            + java.net.URLEncoder.encode(val.toString(), "UTF-8");
                    clientRequest.setResourceRef(newRef);
                }
            } catch (IOException | RuntimeException e) {
                logger.debug("Skipping parameter '{}' due to resolution error", spec.getName(), e);
            }
        }
    }

    /**
     * Build a mapped JSON node from the output parameter specification and the client response
     * root.
     */
    public static JsonNode resolveOutputMappings(OutputParameterSpec spec, JsonNode clientRoot,
            ObjectMapper mapper) {
        return resolveOutputMappings(spec, clientRoot, mapper, null);
    }

    /**
     * Build a mapped JSON node from the output parameter specification and the client response
     * root, optionally resolving Mustache templates in {@code value} fields.
     *
     * <p>Extracted values are passed through with their upstream JSON type. Use
     * {@link #resolveExposedOutputMappings} for an exposed output contract, where values must be
     * coerced to their declared type.</p>
     *
     * @param parameters input parameters for Mustache resolution (may be null)
     */
    public static JsonNode resolveOutputMappings(OutputParameterSpec spec, JsonNode clientRoot,
            ObjectMapper mapper, Map<String, Object> parameters) {
        return resolveOutputMappings(spec, clientRoot, mapper, parameters, false);
    }

    /**
     * Build a mapped JSON node for an <em>exposed</em> output contract (MCP tool result, REST
     * response, aggregate flow output): same shaping as {@link #resolveOutputMappings}, but every
     * extracted value is coerced to its declared type with {@link #coerceToDeclaredType}, so the
     * result conforms to the types the contract advertises.
     */
    public static JsonNode resolveExposedOutputMappings(OutputParameterSpec spec,
            JsonNode clientRoot, ObjectMapper mapper) {
        return resolveOutputMappings(spec, clientRoot, mapper, null, true);
    }

    static JsonNode resolveOutputMappings(OutputParameterSpec spec, JsonNode clientRoot,
            ObjectMapper mapper, Map<String, Object> parameters, boolean coerce) {
        if (spec == null) {
            return NullNode.instance;
        }

        String type = spec.getType();

        // value takes precedence — used for static/mock values
        if (spec.getValue() != null) {
            String resolved = resolveMustacheTemplate(spec.getValue(), parameters);
            return mapper.getNodeFactory().textNode(resolved);
        }

        if ("array".equalsIgnoreCase(type)) {
            JsonNode arrayRoot = Converter.jsonPathExtract(clientRoot, spec.getMapping());

            if (arrayRoot == null || !arrayRoot.isArray()) {
                return NullNode.instance;
            }

            ArrayNode outArray = mapper.createArrayNode();
            OutputParameterSpec items = spec.getItems();

            for (JsonNode element : arrayRoot) {
                if (items != null) {
                    // If item is an object with properties, build object
                    if ("object".equalsIgnoreCase(items.getType()) && items.getProperties() != null
                            && !items.getProperties().isEmpty()) {
                        ObjectNode outObj = mapper.createObjectNode();

                        for (OutputParameterSpec prop : items.getProperties()) {
                            String propName = prop.getName();
                            JsonNode val = resolveNestedProperty(prop, element, mapper, parameters,
                                    coerce);

                            if (val == null || val instanceof NullNode) {
                                outObj.putNull(propName);
                            } else {
                                outObj.set(propName, val);
                            }
                        }

                        outArray.add(outObj);
                    } else {
                        // primitive or item-level mapping
                        if (items.getMapping() != null) {
                            JsonNode val = Converter.jsonPathExtract(element, items.getMapping());
                            val = Converter.applyMaxLengthIfNeeded(items, val);
                            if (coerce) {
                                val = coerceToDeclaredType(items, val, mapper);
                            }
                            outArray.add(val == null ? NullNode.instance : val);
                        } else {
                            outArray.add(coerce ? coerceToDeclaredType(items, element, mapper)
                                    : element);
                        }
                    }
                } else {
                    outArray.add(element);
                }
            }

            return outArray;
        } else if ("object".equalsIgnoreCase(type)) {
            // If this object represents a map of values, support spec.values
            if (spec.getValues() != null && spec.getMapping() != null) {
                JsonNode mapRoot = Converter.jsonPathExtract(clientRoot, spec.getMapping());

                if (mapRoot == null || !mapRoot.isObject()) {
                    return NullNode.instance;
                }

                ObjectNode outObj = mapper.createObjectNode();
                OutputParameterSpec valuesSpec = spec.getValues();

                mapRoot.properties().forEach(entry -> {
                    JsonNode mappedVal = null;
                    if (valuesSpec.getMapping() != null) {
                        mappedVal = Converter.jsonPathExtract(entry.getValue(),
                                valuesSpec.getMapping());
                    } else {
                        mappedVal = entry.getValue();
                    }
                    mappedVal = Converter.applyMaxLengthIfNeeded(valuesSpec, mappedVal);
                    if (coerce) {
                        mappedVal = coerceToDeclaredType(valuesSpec, mappedVal, mapper);
                    }
                    outObj.set(entry.getKey(), mappedVal == null ? NullNode.instance : mappedVal);
                });

                return outObj;
            }

            ObjectNode outObj = mapper.createObjectNode();
            for (OutputParameterSpec prop : spec.getProperties()) {
                String propName = prop.getName();
                JsonNode val = resolveNestedProperty(prop, clientRoot, mapper, parameters, coerce);
                if (val == null || val instanceof NullNode) {
                    outObj.putNull(propName);
                } else {
                    outObj.set(propName, val);
                }
            }
            return outObj;
        } else {
            // primitive/value mapping
            JsonNode v = Converter.jsonPathExtract(clientRoot, spec.getMapping());
            v = Converter.applyMaxLengthIfNeeded(spec, v);
            if (coerce) {
                v = coerceToDeclaredType(spec, v, mapper);
            }
            return v == null ? NullNode.instance : v;
        }
    }

    /**
     * Coerce an extracted value to the parameter's declared type, so an exposed output conforms to
     * the type it advertises. Values that cannot be represented in the declared type become
     * {@code null} (every advertised value type is nullable):
     *
     * <ul>
     *   <li>{@code string} — text is kept; numbers and booleans become their text; objects and
     *       arrays become their JSON text;</li>
     *   <li>{@code number} — numbers are kept; numeric text is parsed; anything else is
     *       {@code null};</li>
     *   <li>{@code boolean} — booleans are kept; the text {@code true}/{@code false} (any case) is
     *       parsed; anything else is {@code null};</li>
     *   <li>{@code object} / {@code array} — kept only when the value has that JSON type;</li>
     *   <li>missing or unknown declared type — the value is returned unchanged.</li>
     * </ul>
     *
     * @param spec   the declared parameter (may be {@code null})
     * @param node   the extracted value (may be {@code null})
     * @param mapper Jackson mapper used to serialize containers declared as {@code string}
     * @return the coerced value, {@link NullNode} when not representable, or {@code node} as-is
     *         when it is {@code null}/JSON null or no known type is declared
     */
    public static JsonNode coerceToDeclaredType(OutputParameterSpec spec, JsonNode node,
            ObjectMapper mapper) {
        if (node == null || node.isNull() || node.isMissingNode() || spec == null
                || spec.getType() == null) {
            return node == null || node.isMissingNode() ? NullNode.instance : node;
        }

        JsonNodeFactory factory = JsonNodeFactory.instance;
        switch (spec.getType().toLowerCase()) {
            case "string":
                if (node.isTextual()) {
                    return node;
                }
                if (node.isValueNode()) {
                    return factory.textNode(node.asText());
                }
                try {
                    return factory.textNode(mapper.writeValueAsString(node));
                } catch (IOException e) {
                    return NullNode.instance;
                }
            case "number":
                if (node.isNumber()) {
                    return node;
                }
                if (node.isTextual()) {
                    try {
                        // Validate as a decimal, then parse its canonical form as JSON so the
                        // coerced node has the same type (int, long, double...) as a number that
                        // arrived as a JSON number.
                        String canonical = new BigDecimal(node.asText().trim()).toString();
                        return mapper.readTree(canonical);
                    } catch (NumberFormatException | IOException e) {
                        return NullNode.instance;
                    }
                }
                return NullNode.instance;
            case "boolean":
                if (node.isBoolean()) {
                    return node;
                }
                if (node.isTextual()) {
                    String text = node.asText().trim();
                    if ("true".equalsIgnoreCase(text)) {
                        return factory.booleanNode(true);
                    }
                    if ("false".equalsIgnoreCase(text)) {
                        return factory.booleanNode(false);
                    }
                }
                return NullNode.instance;
            case "object":
                return node.isObject() ? node : NullNode.instance;
            case "array":
                return node.isArray() ? node : NullNode.instance;
            default:
                return node;
        }
    }

    /**
     * Shape the object assembled by orchestrated step {@code mappings} against the declared
     * (orchestrated) {@code outputParameters}, so it conforms to the output contract they
     * advertise:
     *
     * <ul>
     *   <li>every declared parameter is present — {@code null} when its mapping did not
     *       resolve;</li>
     *   <li>a declared {@code object} with {@code properties} keeps every declared property
     *       (null-filled) and is {@code null} when the mapped value is not an object;</li>
     *   <li>a declared {@code array} with {@code items} has each element shaped by its
     *       {@code items} declaration and is {@code null} when the mapped value is not an
     *       array;</li>
     *   <li>leaf values are coerced with {@link #coerceToDeclaredType}.</li>
     * </ul>
     *
     * <p>Keys that are mapped but not declared are kept unchanged, so the shaping never removes
     * data a caller already receives.</p>
     *
     * @param outputParameters the declared orchestrated output parameters (may be {@code null})
     * @param assembled        the object assembled from step mappings
     * @param mapper           Jackson mapper
     * @return the shaped object, or {@code assembled} unchanged when nothing is declared
     */
    public static ObjectNode shapeStepOutput(List<OutputParameterSpec> outputParameters,
            ObjectNode assembled, ObjectMapper mapper) {
        if (outputParameters == null || outputParameters.isEmpty() || assembled == null) {
            return assembled;
        }
        ObjectNode shaped = assembled.deepCopy();
        for (OutputParameterSpec param : outputParameters) {
            if (param != null && param.getName() != null) {
                shaped.set(param.getName(),
                        shapeStepValue(param, assembled.get(param.getName()), mapper));
            }
        }
        return shaped;
    }

    /**
     * Shape one orchestrated output value against its declaration.
     *
     * @see #shapeStepOutput(List, ObjectNode, ObjectMapper)
     */
    static JsonNode shapeStepValue(OutputParameterSpec param, JsonNode value,
            ObjectMapper mapper) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return NullNode.instance;
        }
        String type = param.getType() != null ? param.getType().toLowerCase() : null;

        if ("object".equals(type)) {
            if (!value.isObject()) {
                return NullNode.instance;
            }
            if (param.getProperties() == null || param.getProperties().isEmpty()) {
                return value;
            }
            ObjectNode out = ((ObjectNode) value).deepCopy();
            for (OutputParameterSpec prop : param.getProperties()) {
                if (prop != null && prop.getName() != null) {
                    out.set(prop.getName(), shapeStepValue(prop, value.get(prop.getName()), mapper));
                }
            }
            return out;
        }

        if ("array".equals(type)) {
            if (!value.isArray()) {
                return NullNode.instance;
            }
            if (param.getItems() == null) {
                return value;
            }
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode element : value) {
                out.add(shapeStepValue(param.getItems(), element, mapper));
            }
            return out;
        }

        return coerceToDeclaredType(param, value, mapper);
    }

    /**
     * Build a mock JSON object from output parameter {@code value} fields.
     * Mustache templates in values are resolved against the given parameters.
     *
     * @param outputParameters the output parameter specs
     * @param mapper           Jackson mapper
     * @param parameters       input parameters for Mustache resolution (may be null)
     * @return a JSON object, or {@code null} if no values could be built
     */
    public static JsonNode buildMockData(List<OutputParameterSpec> outputParameters,
            ObjectMapper mapper, Map<String, Object> parameters) {
        if (outputParameters == null || outputParameters.isEmpty()) {
            return null;
        }

        ObjectNode result = mapper.createObjectNode();

        for (OutputParameterSpec param : outputParameters) {
            JsonNode paramValue = buildMockValue(param, mapper, parameters);
            if (paramValue != null && !(paramValue instanceof NullNode)) {
                String fieldName = param.getName() != null ? param.getName() : "value";
                result.set(fieldName, paramValue);
            }
        }

        return result.size() > 0 ? result : null;
    }

    /**
     * Build a mock JSON node for a single output parameter. Resolves Mustache templates
     * in {@code value} fields and recurses into nested objects and arrays.
     *
     * @param param      the output parameter spec
     * @param mapper     Jackson mapper
     * @param parameters input parameters for Mustache resolution (may be null)
     * @return the mock JSON node, or {@link NullNode} if no value could be built
     */
    public static JsonNode buildMockValue(OutputParameterSpec param, ObjectMapper mapper,
            Map<String, Object> parameters) {
        if (param == null) {
            return NullNode.instance;
        }

        if (param.getValue() != null) {
            String resolved = resolveMustacheTemplate(param.getValue(), parameters);
            return mapper.getNodeFactory().textNode(resolved);
        }

        String type = param.getType();

        if ("array".equalsIgnoreCase(type)) {
            ArrayNode arrayNode = mapper.createArrayNode();
            OutputParameterSpec items = param.getItems();
            if (items != null) {
                JsonNode itemValue = buildMockValue(items, mapper, parameters);
                if (itemValue != null && !(itemValue instanceof NullNode)) {
                    arrayNode.add(itemValue);
                }
            }
            return arrayNode;
        }

        if ("object".equalsIgnoreCase(type)) {
            ObjectNode objectNode = mapper.createObjectNode();
            if (param.getProperties() != null) {
                for (OutputParameterSpec prop : param.getProperties()) {
                    JsonNode propValue = buildMockValue(prop, mapper, parameters);
                    if (propValue != null && !(propValue instanceof NullNode)) {
                        String propName = prop.getName() != null ? prop.getName() : "property";
                        objectNode.set(propName, propValue);
                    }
                }
            }
            return objectNode.size() > 0 ? objectNode : NullNode.instance;
        }

        return NullNode.instance;
    }

    /**
     * Resolve a nested output property (an object property or an array item property) against
     * {@code root}, honouring its declared shape the same way a root-level parameter is honoured:
     *
     * <ul>
     *   <li>a static {@code value} is emitted as-is (Mustache-resolved);</li>
     *   <li>an {@code array} with a {@code mapping} and {@code items} is shaped element by element;
     *   </li>
     *   <li>an {@code object} with {@code values} and a {@code mapping} is shaped as a map;</li>
     *   <li>an {@code object} with {@code properties} is assembled from them — against
     *       {@code root} when it has no {@code mapping}, or against the extracted node when it
     *       has one (the same relative rule that applies to array {@code items});</li>
     *   <li>anything else is extracted by its {@code mapping} and passed through.</li>
     * </ul>
     */
    static JsonNode resolveNestedProperty(OutputParameterSpec prop, JsonNode root,
            ObjectMapper mapper, Map<String, Object> parameters) {
        return resolveNestedProperty(prop, root, mapper, parameters, false);
    }

    static JsonNode resolveNestedProperty(OutputParameterSpec prop, JsonNode root,
            ObjectMapper mapper, Map<String, Object> parameters, boolean coerce) {
        if (prop.getValue() != null) {
            return resolveOutputMappings(prop, root, mapper, parameters, coerce);
        }

        String type = prop.getType();
        String mapping = prop.getMapping();

        if ("array".equalsIgnoreCase(type) && mapping != null && prop.getItems() != null) {
            return resolveOutputMappings(prop, root, mapper, parameters, coerce);
        }

        if ("object".equalsIgnoreCase(type)) {
            if (prop.getValues() != null && mapping != null) {
                return resolveOutputMappings(prop, root, mapper, parameters, coerce);
            }
            if (prop.getProperties() != null && !prop.getProperties().isEmpty()) {
                if (mapping == null) {
                    return resolveOutputMappings(prop, root, mapper, parameters, coerce);
                }
                JsonNode subRoot = Converter.jsonPathExtract(root, mapping);
                if (subRoot == null || !subRoot.isObject()) {
                    return NullNode.instance;
                }
                return resolveOutputMappings(prop, subRoot, mapper, parameters, coerce);
            }
        }

        JsonNode val = Converter.jsonPathExtract(root, mapping);
        val = Converter.applyMaxLengthIfNeeded(prop, val);
        return coerce ? coerceToDeclaredType(prop, val, mapper) : val;
    }
}

