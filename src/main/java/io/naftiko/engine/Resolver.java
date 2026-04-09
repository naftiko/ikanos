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
package io.naftiko.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.restlet.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.samskivert.mustache.Mustache;
import io.naftiko.spec.InputParameterSpec;
import io.naftiko.spec.OutputParameterSpec;

/**
 * Utility class for resolving Mustache-style template strings with provided parameters.
 */
public class Resolver {

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
                    serialized.put(entry.getKey(), jsonMapper.writeValueAsString(val));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    serialized.put(entry.getKey(), val);
                }
            } else {
                serialized.put(entry.getKey(), val);
            }
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
        } catch (Exception e) {
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
            } catch (Exception e) {
                // ignore individual param errors and continue
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
     * @param parameters input parameters for Mustache resolution (may be null)
     */
    public static JsonNode resolveOutputMappings(OutputParameterSpec spec, JsonNode clientRoot,
            ObjectMapper mapper, Map<String, Object> parameters) {
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
                            String propMapping = prop.getMapping();
                            JsonNode val = Converter.jsonPathExtract(element, propMapping);
                            val = Converter.applyMaxLengthIfNeeded(prop, val);

                            if (val == null) {
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
                            outArray.add(val == null ? NullNode.instance : val);
                        } else {
                            outArray.add(element);
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
                    outObj.set(entry.getKey(), mappedVal == null ? NullNode.instance : mappedVal);
                });

                return outObj;
            }

            ObjectNode outObj = mapper.createObjectNode();
            for (OutputParameterSpec prop : spec.getProperties()) {
                String propName = prop.getName();
                String propMapping = prop.getMapping();
                JsonNode val;
                if (propMapping == null && "object".equalsIgnoreCase(prop.getType())
                        && prop.getProperties() != null && !prop.getProperties().isEmpty()) {
                    val = resolveOutputMappings(prop, clientRoot, mapper);
                } else {
                    val = Converter.jsonPathExtract(clientRoot, propMapping);
                    val = Converter.applyMaxLengthIfNeeded(prop, val);
                }
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
            return v == null ? NullNode.instance : v;
        }
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

}

