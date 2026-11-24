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
package io.ikanos.engine.consumes.contract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.ikanos.engine.util.CallerRoot;
import io.ikanos.engine.util.InputDefaults;
import io.ikanos.spec.InputParameterSpec;
import io.ikanos.spec.util.IkanosMappers;

/**
 * Builds the request tree of a contract-described consumed call from the calling step's values:
 * the request message of a gRPC method, the variables of a GraphQL document, the input structure
 * of a Smithy operation.
 *
 * <p>Each declared input takes the step's value of the same name, or its {@code default}. The
 * value is written at the input's {@code wireName}, a dotted path into the tree (default: the
 * input's name). An absent optional input is left out, never sent as {@code null}. A value given
 * as text for an {@code integer}, {@code number} or {@code boolean} input is converted, since step
 * values are often rendered from templates; any other value must match the input's {@code type}.
 * A JSON {@code null} counts as absent.</p>
 */
public final class ContractRequest {

    private static final ObjectMapper JSON = IkanosMappers.json();

    private final ObjectNode tree;
    private final Set<String> present;

    private ContractRequest(ObjectNode tree, Set<String> present) {
        this.tree = tree;
        this.present = present;
    }

    /** @return the request tree */
    public ObjectNode tree() {
        return tree;
    }

    /** @return the names of the inputs that have a value, in declaration order */
    public Set<String> present() {
        return present;
    }

    /**
     * @param inputs the declared inputs
     * @param parameters the calling step's values, keyed by input name
     * @param where the operation, for error messages
     * @return the request
     * @throws IllegalArgumentException when a required input has no value, or a value has the
     *         wrong type
     */
    public static ContractRequest build(List<InputParameterSpec> inputs,
            Map<String, Object> parameters, String where) {
        Map<String, Object> effective = InputDefaults.apply(inputs, parameters);
        ObjectNode tree = JSON.createObjectNode();
        Set<String> present = new LinkedHashSet<>();
        for (InputParameterSpec input : inputs) {
            Object value = effective != null ? effective.get(input.getName()) : null;
            if (value instanceof CallerRoot) {
                // The caller root is no call parameter: sent upstream, it would hand every claim
                // of the token to the upstream (#998)
                value = null;
            }
            if (value instanceof JsonNode node && (node.isNull() || node.isMissingNode())) {
                // A JSON null is no value: left out, or refused when required
                value = null;
            }
            if (value == null) {
                if (input.isRequired()) {
                    throw new IllegalArgumentException("Missing required input '"
                            + input.getName() + "' for " + where);
                }
                continue;
            }
            put(tree, path(input), convert(input, value, where));
            present.add(input.getName());
        }
        return new ContractRequest(tree, Collections.unmodifiableSet(present));
    }

    /** @return the dotted path an input is written at: its {@code wireName}, or its name */
    public static List<String> path(InputParameterSpec input) {
        String wire = input.getWireName();
        String path = wire != null && !wire.isBlank() ? wire : input.getName();
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Input '" + input.getName()
                        + "' has an empty segment in wireName '" + path + "'");
            }
            segments.add(segment);
        }
        return segments;
    }

    static void put(ObjectNode tree, List<String> path, JsonNode value) {
        ObjectNode node = tree;
        for (int i = 0; i < path.size() - 1; i++) {
            String segment = path.get(i);
            JsonNode child = node.get(segment);
            if (child == null || child.isNull()) {
                node = node.putObject(segment);
            } else if (child instanceof ObjectNode object) {
                node = object;
            } else {
                throw new IllegalArgumentException("Two inputs write '" + String.join(".", path)
                        + "': '" + segment + "' is already a value");
            }
        }
        String last = path.get(path.size() - 1);
        if (node.has(last)) {
            throw new IllegalArgumentException(
                    "Two inputs write '" + String.join(".", path) + "'");
        }
        node.set(last, value);
    }

    /**
     * The most digits a number may have before or after its point. A short text such as
     * {@code 1e20000000}, or {@code 1e-20000000}, is a number of twenty million digits, which an
     * exact conversion takes unbounded time to build, before the call and its timeout start.
     */
    public static final int MAX_NUMBER_DIGITS = 1000;

    /**
     * Refuses a nonzero number with more than {@link #MAX_NUMBER_DIGITS} integral digits, or more
     * than {@link #MAX_NUMBER_DIGITS} zeros after its point before its first significant digit,
     * to call before any exact conversion of an input value.
     *
     * @return the number itself
     * @throws ArithmeticException when it is too large or too small
     */
    public static BigDecimal boundedNumber(BigDecimal value) {
        if (value.signum() != 0) {
            long integral = (long) value.precision() - value.scale();
            if (integral > MAX_NUMBER_DIGITS || -integral > MAX_NUMBER_DIGITS) {
                throw new ArithmeticException("more than " + MAX_NUMBER_DIGITS + " digits");
            }
        }
        return value;
    }

    static JsonNode convert(InputParameterSpec input, Object value, String where) {
        String type = input.getType();
        if (value instanceof JsonNode json && json.isString()) {
            // A JSON string is text, converted as such
            value = json.asString();
        }
        if (value instanceof String text && type != null) {
            try {
                switch (type) {
                    case "integer":
                        return JSON.getNodeFactory().numberNode(
                                boundedNumber(new BigDecimal(text.trim())).toBigIntegerExact());
                    case "number":
                        return JSON.getNodeFactory().numberNode(
                                boundedNumber(new BigDecimal(text.trim())));
                    case "boolean":
                        if ("true".equalsIgnoreCase(text.trim())
                                || "false".equalsIgnoreCase(text.trim())) {
                            return JSON.getNodeFactory().booleanNode(
                                    Boolean.parseBoolean(text.trim()));
                        }
                        throw new IllegalArgumentException("not a boolean");
                    case "object":
                    case "array":
                        JsonNode parsed = JSON.readTree(text);
                        if (("object".equals(type) && parsed.isObject())
                                || ("array".equals(type) && parsed.isArray())) {
                            return parsed;
                        }
                        throw new IllegalArgumentException("not an " + type);
                    default:
                        return JSON.getNodeFactory().stringNode(text);
                }
            } catch (ArithmeticException | IllegalArgumentException
                    | tools.jackson.core.JacksonException e) {
                throw new IllegalArgumentException("Input '" + input.getName() + "' of " + where
                        + " is not a valid " + type, e);
            }
        }
        JsonNode node = JSON.valueToTree(value);
        if (type == null) {
            return node;
        }
        switch (type) {
            case "string":
                if (node.isNumber() || node.isBoolean()) {
                    return JSON.getNodeFactory().stringNode(node.asString());
                }
                return checked(node.isString(), node, input, where);
            case "integer":
                if (node.isNumber() && !node.isIntegralNumber()) {
                    try {
                        return JSON.getNodeFactory().numberNode(
                                boundedNumber(node.decimalValue()).toBigIntegerExact());
                    } catch (ArithmeticException e) {
                        throw invalid(input, where, e);
                    }
                }
                return checked(node.isIntegralNumber(), node, input, where);
            case "number":
                return checked(node.isNumber(), node, input, where);
            case "boolean":
                return checked(node.isBoolean(), node, input, where);
            case "object":
                return checked(node.isObject(), node, input, where);
            case "array":
                return checked(node.isArray(), node, input, where);
            default:
                return node;
        }
    }

    private static JsonNode checked(boolean valid, JsonNode node, InputParameterSpec input,
            String where) {
        if (!valid) {
            throw invalid(input, where, null);
        }
        return node;
    }

    private static IllegalArgumentException invalid(InputParameterSpec input, String where,
            Exception cause) {
        return new IllegalArgumentException("Input '" + input.getName() + "' of " + where
                + " is not a valid " + input.getType(), cause);
    }
}
