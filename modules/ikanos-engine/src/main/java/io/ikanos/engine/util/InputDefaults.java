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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.aggregates.AggregateFlowSpec;
import io.ikanos.spec.aggregates.AggregateSpec;
import io.ikanos.spec.consumes.ClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientSpec;
import io.ikanos.spec.consumes.mcp.McpClientToolSpec;
import io.ikanos.spec.exposes.ServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerSpec;
import io.ikanos.spec.exposes.mcp.McpServerToolSpec;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code default} of an MCP tool input parameter (#979): how it fills an argument, and the
 * load-time checks of the declared value.
 *
 * <p>{@code McpToolInputParameter} is used by exposed MCP tools, aggregate flows and consumed
 * MCP tools, so the same rule applies to the three: when a caller leaves an optional input out or
 * passes {@code null}, the default is used as the argument value, with its JSON type. A default on
 * a required input never applies (the caller must send the input), and is reported as a
 * warning.</p>
 *
 * <p>The {@code examples} of the same parameters, and of their nested {@code items} and
 * {@code properties}, are checked at load the same way: each one, read as the tool's
 * {@code inputSchema} advertises it ({@link #typedExampleValue}), must match the type and the
 * enum, since JSON Schema says examples SHOULD validate against their schema and models learn
 * arguments from them.</p>
 */
public final class InputDefaults {

    private static final ObjectMapper JSON = IkanosMappers.json();

    /** The most digits an integer example is expanded to, far beyond any real argument. */
    private static final int MAX_EXAMPLE_INTEGER_DIGITS = 1000;

    private InputDefaults() {
    }

    /**
     * Fill the arguments that the caller left out, or passed as {@code null}, with the defaults
     * of the optional parameters that declare one.
     *
     * @param parameters the declared input parameters, may be null
     * @param arguments the caller arguments, may be null
     * @return {@code arguments} itself when no default applies, otherwise a new map holding the
     *         arguments and the defaults
     */
    public static Map<String, Object> apply(Collection<InputParameterSpec> parameters,
            Map<String, Object> arguments) {
        if (parameters == null) {
            return arguments;
        }
        Map<String, Object> applied = arguments;
        for (InputParameterSpec param : parameters) {
            if (param == null || param.getName() == null) {
                continue;
            }
            Object current = applied != null ? applied.get(param.getName()) : null;
            Object value = valueOrDefault(param, current);
            if (value != current) {
                if (applied == arguments) {
                    // A new map: the caller's may be immutable or reject null values
                    applied = arguments != null ? new HashMap<>(arguments) : new HashMap<>();
                }
                applied.put(param.getName(), value);
            }
        }
        return applied;
    }

    /**
     * @param param the declared input parameter
     * @param value the caller value, may be null or a JSON {@code null} node (a REST body of
     *        {@code null} bound to an untyped body input)
     * @return {@code value} when it is not null, or when the parameter is required or declares no
     *         default; otherwise the default, as a new plain Java value (a {@code String},
     *         {@code Number}, {@code Boolean}, {@code List} or {@code Map}) for every call
     */
    public static Object valueOrDefault(InputParameterSpec param, Object value) {
        boolean absent = value == null || (value instanceof JsonNode node && node.isNull());
        if (!absent || param == null || param.isRequired()
                || param.getDefaultValue() == null) {
            return value;
        }
        return JSON.treeToValue(param.getDefaultValue(), Object.class);
    }

    /**
     * @param spec the capability spec; {@code null} is a no-op
     * @throws IllegalArgumentException listing every default and every example that does not
     *         match its parameter
     */
    public static void validate(IkanosSpec spec) {
        List<String> problems = new ArrayList<>();
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            problems.add("Invalid input default" + (errors.size() > 1 ? "s" : "") + ": "
                    + String.join("; ", errors));
        }
        List<String> exampleErrors = findExampleErrors(spec);
        if (!exampleErrors.isEmpty()) {
            problems.add("Invalid input example" + (exampleErrors.size() > 1 ? "s" : "") + ": "
                    + String.join("; ", exampleErrors));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per default that does not match its parameter's {@code type} or
     *         {@code enum}: exposed MCP tools, then aggregate flows, then consumed MCP tools
     */
    public static List<String> findErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        forEachParameter(spec, (label, param) -> {
            String error = defaultError(param);
            if (error != null) {
                errors.add(label + ": " + error);
            }
        });
        return errors;
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per example, on a parameter or on its nested {@code items} and
     *         {@code properties}, that does not match its type or enum: exposed MCP tools, then
     *         aggregate flows, then consumed MCP tools
     */
    public static List<String> findExampleErrors(IkanosSpec spec) {
        List<String> errors = new ArrayList<>();
        forEachParameter(spec, (label, param) -> {
            for (String error : examplesErrors(param)) {
                errors.add(label + ": " + error);
            }
        });
        return errors;
    }

    /**
     * @param spec the capability spec, may be null
     * @return one message per required parameter that declares a default, which never applies
     */
    public static List<String> findWarnings(IkanosSpec spec) {
        List<String> warnings = new ArrayList<>();
        forEachParameter(spec, (label, param) -> {
            if (param.isRequired() && param.getDefaultValue() != null) {
                warnings.add(label + " is required, so its default never applies");
            }
        });
        return warnings;
    }

    /**
     * Check a parameter's default against its {@code type} and {@code enum}, the way a JSON
     * Schema validator reads the advertised schema: no conversion, so {@code "200"} does not match
     * {@code integer}. Enum values are written as strings and read as the tool's
     * {@code inputSchema} advertises them ({@link #typedEnumValue}): a number default matches an
     * enum value advertised as a number with the same numeric value.
     *
     * @return the problem, or {@code null} when the default is absent or matches
     */
    static String defaultError(InputParameterSpec param) {
        return defaultError(param.getDefaultValue(), param.getType(), param.getEnumeration());
    }

    /**
     * Check a default against a {@code type} and an {@code enum} declared elsewhere, such as in
     * the schema that a {@code schema:} reference resolves to, as {@link #defaultError} does.
     *
     * @param value the default, may be null
     * @param type the type the default must match, may be null
     * @param enumeration the values the default must be one of, empty for any
     * @return the problem, or {@code null} when the default is absent or matches
     */
    public static String defaultError(JsonNode value, String type, List<String> enumeration) {
        if (value == null) {
            return null;
        }
        String error = valueError(value, type, enumeration);
        return error != null ? "default " + error : null;
    }

    /**
     * Check the examples of a parameter, and of its nested {@code items} and {@code properties},
     * with {@link #exampleError}.
     *
     * @param param the parameter
     * @return one message per example that does not match, naming the nested property or the
     *         {@code items} that declares it
     */
    static List<String> examplesErrors(InputParameterSpec param) {
        List<String> errors = new ArrayList<>();
        collectExamplesErrors(param, "", errors);
        return errors;
    }

    private static void collectExamplesErrors(InputParameterSpec param, String location,
            List<String> errors) {
        for (String example : param.getExamples()) {
            String error = exampleError(example, param.getType(), param.getEnumeration());
            if (error != null) {
                errors.add(location + error);
            }
        }
        if (param.getItems() != null) {
            collectExamplesErrors(param.getItems(), location + "items: ", errors);
        }
        for (InputParameterSpec child : param.getProperties()) {
            if (child != null) {
                collectExamplesErrors(child, location + "property '" + child.getName() + "': ",
                        errors);
            }
        }
    }

    /**
     * Check an example the way {@link #defaultError} checks a default, on the value that the
     * tool's {@code inputSchema} advertises: examples are stored as text, like enum values, and
     * read with {@link #typedExampleValue}. An untyped input is advertised as a {@code string}.
     * Numbers are read as for a default: an integral decimal such as {@code 25.0} is an
     * {@code integer}, {@code 2.5} is not.
     *
     * @param example the example as written
     * @param type the input type, may be null (read as {@code string})
     * @param enumeration the values the example must be one of, empty for any
     * @return the problem, or {@code null} when the example matches
     */
    public static String exampleError(String example, String type, List<String> enumeration) {
        if (example == null) {
            return null;
        }
        String advertised = type != null ? type : InputParameterSpec.DEFAULT_MCP_TOOL_INPUT_TYPE;
        JsonNode value = JSON.valueToTree(typedExampleValue(advertised, example));
        String error = valueError(value, advertised, enumeration);
        return error != null ? "example '" + example + "' " + error : null;
    }

    /**
     * @return what is wrong with a value for a {@code type} and an {@code enum}, as the end of a
     *         sentence such as {@code does not match type 'integer'}, or {@code null}
     */
    private static String valueError(JsonNode value, String type, List<String> enumeration) {
        if (type != null && !matchesType(type, value)) {
            return "does not match type '" + type + "'";
        }
        if (!enumeration.isEmpty() && !inEnum(type, value, enumeration)) {
            return "is not one of the enum values";
        }
        return null;
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && isIntegral(value);
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
    }

    private static boolean isIntegral(JsonNode value) {
        if (value.isIntegralNumber()) {
            return true;
        }
        try {
            return value.decimalValue().stripTrailingZeros().scale() <= 0;
        } catch (ArithmeticException | NumberFormatException e) {
            // NaN or an infinity
            return false;
        }
    }

    private static boolean inEnum(String type, JsonNode value, List<String> enumeration) {
        if (value.isContainer()) {
            return false;
        }
        for (String candidate : enumeration) {
            JsonNode advertised = JSON.valueToTree(typedEnumValue(type, candidate));
            if (value.isNumber() && advertised.isNumber()) {
                try {
                    if (value.decimalValue().compareTo(advertised.decimalValue()) == 0) {
                        return true;
                    }
                } catch (ArithmeticException | NumberFormatException e) {
                    // NaN or an infinity: equals no enum value
                }
            } else if (value.equals(advertised)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enum values are stored as strings; read one with the parameter's JSON type, so that the
     * advertised schema stays valid for number, integer and boolean parameters. Shared by the
     * advertised {@code inputSchema} and the check of a default, so both read an enum value the
     * same way: a value that does not parse as the type, such as {@code "50.0"} or {@code "1e2"}
     * on an {@code integer} parameter, stays a string.
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
     * Examples are stored as text; read one with the parameter's JSON type, as
     * {@link #typedEnumValue} does for enum values, except that an integral decimal such as
     * {@code 25.0} on an {@code integer} parameter is read as the integer {@code 25}, as
     * {@link #defaultError} accepts {@code 25.0} as an integer default. Shared by the advertised
     * {@code inputSchema} and {@link #exampleError}: a value that does not parse as the type, such
     * as {@code 2.5} on an {@code integer} parameter, stays a string, which
     * {@link #exampleError} reports.
     *
     * @param type the parameter type, may be null (read as {@code string})
     * @param value the example as written
     * @return a {@link BigInteger}, {@link BigDecimal}, {@link Boolean} or the string itself
     */
    public static Object typedExampleValue(String type, String value) {
        if ("integer".equals(type)) {
            try {
                BigDecimal number = new BigDecimal(value);
                // An integer of more digits than any argument has, such as 1e999999999, is not
                // expanded: it stays a string, which exampleError reports
                if (number.signum() != 0
                        && number.precision() - number.scale() > MAX_EXAMPLE_INTEGER_DIGITS) {
                    return value;
                }
                return number.toBigIntegerExact();
            } catch (NumberFormatException | ArithmeticException e) {
                return value;
            }
        }
        return typedEnumValue(type, value);
    }

    /**
     * Visit every {@code McpToolInputParameter}: exposed MCP tools, aggregate flows and consumed
     * MCP tools, in a capability and at the root of a shared section document.
     */
    private static void forEachParameter(IkanosSpec spec,
            BiConsumer<String, InputParameterSpec> visitor) {
        if (spec == null) {
            return;
        }
        List<ServerSpec> exposes = new ArrayList<>();
        List<AggregateSpec> aggregates = new ArrayList<>();
        List<ClientSpec> consumes = new ArrayList<>();
        if (spec.getCapability() != null) {
            addAll(exposes, spec.getCapability().getExposes());
            if (spec.getCapability().getAggregates() != null) {
                addAll(aggregates, spec.getCapability().getAggregates().values());
            }
            addAll(consumes, spec.getCapability().getConsumes());
        }
        addAll(exposes, spec.getExposes());
        addAll(aggregates, spec.getAggregates());
        addAll(consumes, spec.getConsumes());

        for (ServerSpec server : exposes) {
            if (server instanceof McpServerSpec mcp && mcp.getTools() != null) {
                for (Map.Entry<String, McpServerToolSpec> tool : mcp.getTools().entrySet()) {
                    if (tool.getValue() != null) {
                        visit("MCP tool '" + mcp.getNamespace() + "." + tool.getKey() + "'",
                                tool.getValue().getInputParameters(), visitor);
                    }
                }
            }
        }
        for (AggregateSpec aggregate : aggregates) {
            if (aggregate == null || aggregate.getFlows() == null) {
                continue;
            }
            for (Map.Entry<String, AggregateFlowSpec> flow : aggregate.getFlows().entrySet()) {
                if (flow.getValue() != null && flow.getValue().getInputParameters() != null) {
                    visit("aggregate flow '" + aggregate.getNamespace() + "." + flow.getKey()
                            + "'", flow.getValue().getInputParameters().values(), visitor);
                }
            }
        }
        for (ClientSpec client : consumes) {
            if (client instanceof McpClientSpec mcp && mcp.getTools() != null) {
                for (Map.Entry<String, McpClientToolSpec> tool : mcp.getTools().entrySet()) {
                    if (tool.getValue() != null) {
                        visit("consumed MCP tool '" + mcp.getNamespace() + "." + tool.getKey()
                                + "'", tool.getValue().getInputParameters(), visitor);
                    }
                }
            }
        }
    }

    private static void visit(String owner, Collection<InputParameterSpec> parameters,
            BiConsumer<String, InputParameterSpec> visitor) {
        if (parameters == null) {
            return;
        }
        for (InputParameterSpec param : parameters) {
            if (param != null) {
                visitor.accept(owner + " input parameter '" + param.getName() + "'", param);
            }
        }
    }

    private static <T> void addAll(List<T> target, Collection<? extends T> source) {
        if (source != null) {
            for (T item : source) {
                if (item != null) {
                    target.add(item);
                }
            }
        }
    }
}
