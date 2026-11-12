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
 */
public final class InputDefaults {

    private static final ObjectMapper JSON = IkanosMappers.json();

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
     * @throws IllegalArgumentException listing every default that does not match its parameter
     */
    public static void validate(IkanosSpec spec) {
        List<String> errors = findErrors(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid input default"
                    + (errors.size() > 1 ? "s" : "") + ": " + String.join("; ", errors));
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
        JsonNode value = param.getDefaultValue();
        if (value == null) {
            return null;
        }
        String type = param.getType();
        if (type != null && !matchesType(type, value)) {
            return "default does not match type '" + type + "'";
        }
        List<String> enumeration = param.getEnumeration();
        if (!enumeration.isEmpty() && !inEnum(type, value, enumeration)) {
            return "default is not one of the enum values";
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
