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
import tools.jackson.core.JacksonException;
import org.restlet.Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.OutputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Utility class for resolving Mustache-style template strings with provided parameters.
 */
public class Resolver {

    private static final Logger logger = LoggerFactory.getLogger(Resolver.class);

    /**
     * JMustache default value used when there are no parameters at all: JMustache replaces
     * {@code {{name}}} in a default value with the missing variable's name, so a missing variable
     * is rendered back as {@code {{name}}}. Unescaped and spaced forms ({@code {{{x}}}},
     * {@code {{&x}}}, {@code {{ x }}}) are normalised to {@code {{x}}}. The braces are kept so an
     * exposes-side credential template never collapses to an empty string; to reject a missing
     * required value, call {@link #missingVariables(String, Map)} on the template instead of
     * checking the rendered output.
     */
    private static final String UNRESOLVED_VARIABLE = "{{{{name}}}}";

    private Resolver() {
        // Utility class, no instantiation
    }

    /**
     * Resolve Mustache-style templates in a string using provided parameters. Replaces
     * {{paramName}} with the corresponding parameter value from the map using JMustache.
     *
     * <p>Sections are always rendered: {@code {{#x}}...{{/x}}} disappears and
     * {@code {{^x}}...{{/x}}} is kept when {@code x} is absent, even when {@code parameters} is
     * null or empty. A bare variable that is missing renders as an empty string when other
     * parameters are present. When there are no parameters at all, it is rendered back as
     * {@code {{name}}} (unescaped and spaced forms are normalised to that form), so an
     * exposes-side credential template never collapses to an empty string; a string that is not
     * a valid template is then returned unchanged. To reject a missing required value, call
     * {@link #missingVariables(String, Map)} on the template.</p>
     *
     * @param template The template string containing {{...}} placeholders
     * @param parameters Map of parameter names to values for template resolution, may be null
     * @return The rendered template, or {@code null} if {@code template} is null
     */
    public static String resolveMustacheTemplate(String template, Map<String, Object> parameters) {
        return resolveMustacheTemplate(template, parameters, null);
    }

    /**
     * Same as {@link #resolveMustacheTemplate(String, Map)}, with a transformation applied to the
     * text of every value a variable tag renders: top-level parameters, nested paths such as
     * {@code {{step.field}}}, unescaped tags ({@code {{{x}}}}, {@code {{&x}}}) and section items
     * alike. Sections are evaluated on the untransformed values, so a section on a
     * {@code Boolean} behaves exactly as without a transformation. The template's literal text is
     * never transformed. Used, for example, to percent-encode values substituted into a
     * pre-encoded form body. The transformation is not applied when there are no parameters,
     * since no caller value is substituted then.
     *
     * @param template the template string containing {{...}} placeholders
     * @param parameters map of parameter names to values for template resolution, may be null
     * @param valueEncoder transformation of each rendered value, or {@code null} for none
     * @return the rendered template, or {@code null} if {@code template} is null
     */
    public static String resolveMustacheTemplate(String template, Map<String, Object> parameters,
            java.util.function.UnaryOperator<String> valueEncoder) {
        if (template == null) {
            return template;
        }

        boolean noParameters = parameters == null || parameters.isEmpty();

        // JSON-serialize non-scalar values (arrays, maps) so that Mustache substitution
        // produces valid JSON instead of calling toString() (e.g. [CREW-001, CREW-003]).
        Map<String, Object> serialized = new HashMap<>();
        ObjectMapper jsonMapper = IkanosMappers.json();
        if (!noParameters) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof java.util.Collection || val instanceof Object[]) {
                    try {
                        val = jsonMapper.writeValueAsString(val);
                    } catch (JacksonException e) {
                        // keep the plain value
                    }
                }
                serialized.put(entry.getKey(), val);
            }
        }

        // escapeHTML(false): JMustache escapes HTML entities by default (e.g. " → &quot;).
        // This is desirable for HTML output, but templates here produce JSON bodies or URI strings —
        // never HTML. Without this, serialized array values like ["CREW-001"] would be rendered
        // as [&quot;CREW-001&quot;], producing invalid JSON.
        //
        // With no parameters at all, sections are still rendered (so an optional part over an
        // absent key disappears, #847), but a bare missing variable is written back as its own
        // {{name}}, so an exposes-side credential template never collapses to an empty string.
        // Required-value checks do not read this leftover: they call missingVariables(...).
        Mustache.Compiler compiler = Mustache.compiler().escapeHTML(false)
                .defaultValue(noParameters ? UNRESOLVED_VARIABLE : "");
        if (valueEncoder != null && !noParameters) {
            // The formatter runs on every rendered value, whatever its path or tag, and only on
            // rendering: sections still see the original values.
            compiler = compiler.withFormatter(value -> valueEncoder.apply(String.valueOf(value)));
        }
        if (noParameters) {
            // Nothing can be substituted: a string that is not a valid template (for example a
            // static credential containing "{{}}") is returned as-is, as it was before #847.
            try {
                return compileTemplate(compiler, template).execute(serialized);
            } catch (RuntimeException e) {
                // Log the exception type only: JMustache messages quote tag names, which could
                // echo part of a static credential.
                logger.debug("Template is not valid Mustache ({}); returning it unchanged",
                        e.getClass().getSimpleName());
                return template;
            }
        }
        return compileTemplate(compiler, template).execute(serialized);
    }

    /**
     * A string that is exactly one variable tag: {@code {{x}}}, {@code {{ x }}}, {@code {{{x}}}}
     * or {@code {{&x}}}, where {@code x} is a name or a dotted path.
     */
    private static final java.util.regex.Pattern WHOLE_VALUE_VARIABLE = java.util.regex.Pattern
            .compile("\\{\\{\\s*(?:&\\s*)?([^\\s{}#^/!=>&]+)\\s*\\}\\}"
                    + "|\\{\\{\\{\\s*([^\\s{}]+)\\s*\\}\\}\\}");

    /**
     * Resolve a value that may be a whole-value placeholder (#880). When {@code template} is
     * exactly one variable tag ({@code {{x}}}, {@code {{ x }}}, {@code {{{x}}}}, {@code {{&x}}},
     * dotted paths included), the parameter value itself is returned, so a list, a number or a
     * boolean keeps its type; it is looked up as Mustache would look it up, and {@code null} is
     * returned when it is absent or null. Any other template, or any template when there are no
     * parameters at all (so the {@code {{x}}} leftover still flags a missing value), is rendered
     * to text by {@link #resolveMustacheTemplate(String, Map)}.
     *
     * @param template the value, may be null
     * @param parameters map of parameter names to values, may be null
     * @return the parameter value, the rendered text, or {@code null}
     */
    public static Object resolveValue(String template, Map<String, Object> parameters) {
        if (template == null) {
            return null;
        }
        java.util.regex.Matcher matcher = WHOLE_VALUE_VARIABLE.matcher(template);
        if (parameters == null || parameters.isEmpty() || !matcher.matches()) {
            return resolveMustacheTemplate(template, parameters);
        }
        String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (".".equals(name) || "this".equals(name)) {
            return resolveMustacheTemplate(template, parameters);
        }
        return lookupVariable(name, parameters);
    }

    /**
     * The value JMustache would render for {@code {{name}}}, before formatting, or {@code null}
     * when it is absent or null.
     */
    static Object lookupVariable(String name, Map<String, Object> parameters) {
        Object[] found = new Object[1];
        // No default value: a missing or null variable makes JMustache throw.
        Mustache.Compiler compiler = Mustache.compiler().withFormatter(value -> {
            found[0] = value;
            return "";
        });
        try {
            compileTemplate(compiler, "{{" + name + "}}").execute(parameters);
        } catch (MustacheException e) {
            return null;
        }
        return found[0];
    }

    /**
     * Compiles a template exactly as {@link #resolveMustacheTemplate(String, Map)} parses it,
     * literal braces before section tags included (#848). Every code path that compiles a
     * consumed template, to render it or only to inspect it, goes through here, so that checking
     * a template and rendering it cannot disagree.
     *
     * @param template the template, not null
     * @return the compiled template
     * @throws com.samskivert.mustache.MustacheException if the template cannot be parsed
     */
    public static Template compileTemplate(String template) {
        return compileTemplate(Mustache.compiler().escapeHTML(false), template);
    }

    /**
     * Same as {@link #compileTemplate(String)}, with the given compiler settings.
     *
     * @param compiler the compiler to use
     * @param template the template, not null
     * @return the compiled template
     * @throws com.samskivert.mustache.MustacheException if the template cannot be parsed
     */
    public static Template compileTemplate(Mustache.Compiler compiler, String template) {
        return compiler.compile(protectLiteralBraces(template));
    }

    /**
     * Matches a literal "{" written before a section, inverted-section, close or comment tag
     * ({@code {{{#x}}}, {@code {{{^x}}}, {@code {{{/x}}}, {@code {{{!x}}}), including each brace
     * of a run such as {@code {{{{#x}}}. JMustache would otherwise read the brace and the tag as
     * the start of a triple-mustache tag.
     */
    private static final java.util.regex.Pattern BRACE_BEFORE_TAG =
            java.util.regex.Pattern.compile("\\{(?=\\{*\\{\\{[#^/!])");

    /**
     * A literal "{" written with a temporary switch of delimiters: plain template text that needs
     * nothing from the render context, so no parameter can hide it and no value formatter sees it.
     */
    private static final String LITERAL_BRACE = "{{=<% %>=}}{<%={{ }}=%>";

    /**
     * Rewrite every literal "{" written right before a section, inverted-section, close or comment
     * tag as {@link #LITERAL_BRACE} (#848). Unescaped variable tags ({@code {{{x}}}}) are left as
     * they are. A template that sets its own delimiters ({@code {{=...=}}}) is left unchanged:
     * the rewrite assumes the default {@code {{ }}} delimiters.
     */
    static String protectLiteralBraces(String template) {
        if (template == null || !template.contains("{{{") || template.contains("{{=")) {
            return template;
        }
        return BRACE_BEFORE_TAG.matcher(template)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(LITERAL_BRACE));
    }

    /**
     * Lists the top-level variables of a template that have no value in {@code parameters}, in
     * template order (#858). Variables inside a section or an inverted section are not listed:
     * they are optional by construction. For a dotted variable ({@code {{step.id}}}) only the
     * first segment is checked. Checking the template, rather than scanning the rendered output
     * for leftover braces, lets a caller value contain {@code {{...}}} as plain data.
     *
     * <p>An invalid template (for example {@code {{#a}}unclosed}) yields an empty list. This is
     * deliberate: a malformed template is an authoring error, not caller data. With parameters,
     * rendering it fails on its own; with no parameters at all,
     * {@link #resolveMustacheTemplate(String, Map)} returns it unchanged, so it is sent as is
     * rather than rejected (the same rule that keeps a static credential containing
     * {@code {{}}} intact). Consumed templates never get that far:
     * {@code ConsumedTemplateValidator} rejects a malformed one when the capability is loaded
     * (#849).</p>
     *
     * @param template the Mustache template, may be null
     * @param parameters the parameters the template is rendered with, may be null
     * @return the missing variable names, or an empty list (also for an invalid template, which
     *         rendering reports on its own)
     */
    public static List<String> missingVariables(String template, Map<String, Object> parameters) {
        if (template == null || !template.contains("{{")) {
            return List.of();
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        try {
            compileTemplate(template).visit(new Mustache.Visitor() {
                @Override
                public void visitText(String text) {
                    // literal text holds no variable
                }

                @Override
                public void visitVariable(String name) {
                    String first = name.split("\\.", 2)[0].trim();
                    if (!first.isEmpty() && !".".equals(name.trim())
                            && (parameters == null || parameters.get(first) == null)) {
                        missing.add(first);
                    }
                }

                @Override
                public boolean visitInclude(String name) {
                    return false;
                }

                @Override
                public boolean visitSection(String name) {
                    return false;
                }

                @Override
                public boolean visitInvertedSection(String name) {
                    return false;
                }
            });
        } catch (RuntimeException e) {
            logger.debug("Template is not valid Mustache ({})", e.getClass().getSimpleName());
            return List.of();
        }
        return List.copyOf(missing);
    }

    /**
     * Compiles a template through {@link #compileTemplate(String)}, as rendering does, without
     * rendering it, and reports why it cannot be parsed (#849).
     *
     * @param template the template, may be null
     * @return {@code null} when the template compiles (or is null), otherwise the kind of parse
     *         error and its line, for example {@code Section missing close tag (line 1)}
     */
    public static String templateError(String template) {
        if (template == null || !template.contains("{{")) {
            return null;
        }
        try {
            compileTemplate(template);
            return null;
        } catch (RuntimeException e) {
            return describeTemplateError(e);
        }
    }

    private static final java.util.regex.Pattern PARSE_ERROR_LINE =
            java.util.regex.Pattern.compile(" @ line (\\d+)$");

    /**
     * Reduces a JMustache error to its kind and line. JMustache messages quote tag names and
     * template text ({@code Invalid triple-mustache tag: {{{#x}}"}), which could echo part of a
     * static credential, so everything from the first quote, colon or brace on is dropped.
     */
    static String describeTemplateError(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        java.util.regex.Matcher line = PARSE_ERROR_LINE.matcher(message);
        String location = line.find() ? " (line " + line.group(1) + ")" : "";
        String kind = message.split("[:'{]", 2)[0].trim();
        return (kind.isEmpty() ? e.getClass().getSimpleName() : kind) + location;
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

                        if (extracted.isString()) {
                            return extracted.asString();
                        } else {
                            return mapper.convertValue(extracted, Object.class);
                        }
                    }

                    // When a field name is given with no explicit path, extract that field
                    // from the body by name so that named body parameters bind to individual
                    // request fields (e.g. name: shipImo → $.shipImo).
                    if (name != null && root.has(name)) {
                        JsonNode field = root.get(name);
                        if (field.isString()) {
                            return field.asString();
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
                String template = spec.getValue() != null ? spec.getValue() : spec.getTemplate();

                if (template != null) {
                    boolean noParameters = parameters == null || parameters.isEmpty();
                    if (noParameters && !missingVariables(template, parameters).isEmpty()) {
                        // Nothing can be substituted: omit the parameter, so an optional one is
                        // not sent with its raw template text. The check is on the template, not
                        // on the rendered value, so a caller value that itself contains "{{" is
                        // still sent (#858).
                        continue;
                    }
                    // A whole-value placeholder keeps the parameter's type (#880)
                    val = Resolver.resolveValue(template, parameters);
                } else if (parameters != null && parameters.containsKey(spec.getName())) {
                    val = parameters.get(spec.getName());
                } else if ("environment".equalsIgnoreCase(in)) {
                    val = System.getenv(spec.getName());
                }

                if (val == null) {
                    continue;
                } else if (parameters != null) {
                    parameters.put(spec.getName(), val);
                }

                String wireName = spec.getWireName() != null && !spec.getWireName().isBlank()
                        ? spec.getWireName() : spec.getName();
                if ("header".equalsIgnoreCase(in)) {
                    // A list is comma-separated, the OpenAPI "simple" style for headers
                    List<String> elements = listElements(val);
                    clientRequest.getHeaders().set(wireName,
                            elements != null ? String.join(",", elements) : val.toString());
                } else if ("query".equalsIgnoreCase(in)) {
                    String old = clientRequest.getResourceRef().toString();
                    StringBuilder ref = new StringBuilder(old);
                    char separator = old.contains("?") ? '&' : '?';
                    String encodedName = java.net.URLEncoder.encode(wireName, "UTF-8");
                    for (String value : queryValues(spec, val)) {
                        ref.append(separator).append(encodedName).append('=')
                                .append(java.net.URLEncoder.encode(value, "UTF-8"));
                        separator = '&';
                    }
                    clientRequest.setResourceRef(ref.toString());
                }
            } catch (IOException | RuntimeException e) {
                logger.debug("Skipping parameter '{}' due to resolution error", spec.getName(), e);
            }
        }
    }

    /**
     * The values of one query parameter, serialized as in OpenAPI (#851). A scalar gives one
     * value. A list, array or JSON array is serialized by {@code style}: {@code form} with
     * {@code explode} (the default) gives one value per element, so the parameter is repeated;
     * {@code form} without {@code explode} joins the elements with commas; {@code spaceDelimited}
     * and {@code pipeDelimited} join them with a space or {@code |}. An empty list gives no value,
     * so the parameter is left out.
     */
    static List<String> queryValues(InputParameterSpec spec, Object value) {
        List<String> elements = listElements(value);
        if (elements == null) {
            return List.of(value.toString());
        }
        if (elements.isEmpty()) {
            return List.of();
        }
        String style = spec.getStyle() == null ? "form" : spec.getStyle();
        switch (style) {
            case "spaceDelimited":
                return List.of(String.join(" ", elements));
            case "pipeDelimited":
                return List.of(String.join("|", elements));
            default:
                return Boolean.FALSE.equals(spec.getExplode())
                        ? List.of(String.join(",", elements))
                        : elements;
        }
    }

    /** The elements of a list-like value as text, or {@code null} for a scalar. */
    private static List<String> listElements(Object value) {
        Iterable<?> items;
        if (value instanceof java.util.Collection<?> collection) {
            items = collection;
        } else if (value instanceof Object[] array) {
            items = java.util.Arrays.asList(array);
        } else if (value instanceof ArrayNode array) {
            items = array;
        } else {
            return null;
        }
        List<String> elements = new java.util.ArrayList<>();
        for (Object item : items) {
            if (item instanceof JsonNode node) {
                elements.add(node.isValueNode() ? node.asString() : node.toString());
            } else if (item != null) {
                elements.add(item.toString());
            }
        }
        return elements;
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
            return mapper.getNodeFactory().stringNode(resolved);
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
                if (node.isString()) {
                    return node;
                }
                if (node.isValueNode()) {
                    return factory.stringNode(node.asString());
                }
                try {
                    return factory.stringNode(mapper.writeValueAsString(node));
                } catch (JacksonException e) {
                    return NullNode.instance;
                }
            case "number":
                if (node.isNumber()) {
                    return node;
                }
                if (node.isString()) {
                    try {
                        // Validate as a decimal, then parse its canonical form as JSON so the
                        // coerced node has the same type (int, long, double...) as a number that
                        // arrived as a JSON number.
                        String canonical = new BigDecimal(node.asString().trim()).toString();
                        return mapper.readTree(canonical);
                    } catch (NumberFormatException | JacksonException e) {
                        return NullNode.instance;
                    }
                }
                return NullNode.instance;
            case "boolean":
                if (node.isBoolean()) {
                    return node;
                }
                if (node.isString()) {
                    String text = node.asString().trim();
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
            return mapper.getNodeFactory().stringNode(resolved);
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
