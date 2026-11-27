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
package io.ikanos.engine.execution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.common.values.NullValue;
import io.ikanos.spec.util.IkanosMappers;
import tools.jackson.databind.JsonNode;

/**
 * The {@code onlyIf:} guard of a step: whether the step runs.
 *
 * <p>A guard is one of two forms, the same as the {@code $onlyIf} marker of a structured JSON
 * body:</p>
 * <ul>
 * <li>a <b>bare name</b>, a dotted path such as {@code customer.id}, holds when the value is
 * present and is neither {@code null} nor {@code false}; {@code ""}, {@code 0} and the string
 * {@code "false"} hold. An absent value does not hold, so the step is skipped rather than
 * failed;</li>
 * <li>anything else is a <b>CEL expression</b> that must evaluate to a boolean, such as
 * {@code order.status == 'overdue'}. Its variables are the run's parameters (inputs,
 * {@code with} values, earlier step outputs) under the names templates use; a name with
 * {@code -}, which CEL does not allow in an identifier, is also bound with {@code _}
 * ({@code get_ship}). An expression that names a variable the run does not have does not
 * hold either.</li>
 * </ul>
 *
 * <p>The guard only reads values the run already holds, so it gives the same answer when a
 * durable run is replayed from its journal.</p>
 */
public final class StepGuard {

    /** A bare name: dotted segments of letters, digits, {@code _} and {@code -}. */
    private static final Pattern BARE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*");
    /** CEL identifiers; a parameter whose name is not one is bound only under its alias. */
    private static final Pattern CEL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Words a bare name never is: CEL reads them as literals or reserves them. */
    private static final Set<String> CEL_WORDS = Set.of("true", "false", "null", "in", "as",
            "break", "const", "continue", "else", "for", "function", "if", "import", "let",
            "loop", "package", "namespace", "return", "var", "void", "while");

    private static final CelOptions OPTIONS = CelOptions.current()
            .enableHeterogeneousNumericComparisons(true)
            .build();
    private static final CelCompiler PARSER = CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .build();
    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(OPTIONS)
            .build();

    /** Compiled guards, by expression and the variables they were checked against. */
    private static final Map<String, Compiled> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 1024;

    private StepGuard() {
    }

    /** A guard compiled against a set of variables, or the variable it lacks. */
    private record Compiled(CelRuntime.Program program, String missing) {
    }

    /**
     * @param onlyIf the guard
     * @return whether the guard is a bare name, tested for presence, rather than a CEL expression
     */
    public static boolean isBareName(String onlyIf) {
        String guard = onlyIf.strip();
        return BARE_NAME.matcher(guard).matches() && !CEL_WORDS.contains(guard);
    }

    /**
     * Whether a step with this guard runs.
     *
     * @param step the step name, for the error message
     * @param onlyIf the guard, or {@code null} when the step has none
     * @param parameters the values the run holds: inputs, {@code with} values, step outputs
     * @return {@code true} when the step runs
     * @throws IllegalArgumentException when the guard is not a valid CEL expression, fails to
     *         evaluate, or does not evaluate to a boolean
     */
    public static boolean holds(String step, String onlyIf, Map<String, Object> parameters) {
        if (onlyIf == null || onlyIf.isBlank()) {
            return true;
        }
        if (isBareName(onlyIf)) {
            return isPresent(read(parameters, onlyIf.strip()));
        }
        Map<String, Object> variables = variables(parameters);
        Compiled compiled = compile(step, onlyIf, variables.keySet());
        if (compiled.missing() != null) {
            return false;
        }
        Object result;
        try {
            result = compiled.program().eval(variables);
        } catch (CelEvaluationException e) {
            throw new IllegalArgumentException("The onlyIf of step '" + step
                    + "' could not be evaluated: " + e.getMessage(), e);
        }
        if (!(result instanceof Boolean value)) {
            throw new IllegalArgumentException("The onlyIf of step '" + step
                    + "' must evaluate to true or false, not " + describe(result) + ".");
        }
        return value;
    }

    /**
     * Checks that a guard can be read, without evaluating it.
     *
     * @return the parse error, or {@code null} when the guard is a bare name or parses as CEL
     */
    public static String parseError(String onlyIf) {
        if (onlyIf == null || onlyIf.isBlank() || isBareName(onlyIf)) {
            return null;
        }
        CelValidationResult parsed = PARSER.parse(onlyIf);
        return parsed.hasError() ? parsed.getErrorString() : null;
    }

    /**
     * The top-level names a guard reads: the first segment of a bare name, or the free
     * identifiers of a CEL expression that the parser sees (comprehension variables excluded).
     *
     * @return the names, or an empty set when the guard cannot be parsed
     */
    public static Set<String> referencedNames(String onlyIf) {
        Set<String> names = new TreeSet<>();
        if (onlyIf == null || onlyIf.isBlank()) {
            return names;
        }
        if (isBareName(onlyIf)) {
            String guard = onlyIf.strip();
            int dot = guard.indexOf('.');
            names.add(dot < 0 ? guard : guard.substring(0, dot));
            return names;
        }
        CelValidationResult parsed = PARSER.parse(onlyIf);
        if (parsed.hasError()) {
            return names;
        }
        try {
            CelAbstractSyntaxTree ast = parsed.getAst();
            GuardIdentifiers.collect(ast, names);
        } catch (CelValidationException e) {
            return names;
        }
        return names;
    }

    private static Compiled compile(String step, String onlyIf, Set<String> names) {
        String key = String.join("\u0000", new TreeSet<>(names)) + "\u0001" + onlyIf;
        Compiled cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(OPTIONS)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        for (String name : names) {
            builder.addVar(name, SimpleType.DYN);
        }
        CelValidationResult checked = builder.build().compile(onlyIf);
        Compiled compiled;
        if (checked.hasError()) {
            String missing = missingVariable(checked.getErrorString());
            if (missing == null) {
                throw new IllegalArgumentException("The onlyIf of step '" + step
                        + "' is not a valid CEL expression: " + checked.getErrorString());
            }
            compiled = new Compiled(null, missing);
        } else {
            try {
                compiled = new Compiled(RUNTIME.createProgram(checked.getAst()), null);
            } catch (CelValidationException | dev.cel.runtime.CelEvaluationException e) {
                throw new IllegalArgumentException("The onlyIf of step '" + step
                        + "' is not a valid CEL expression: " + e.getMessage(), e);
            }
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, compiled);
        return compiled;
    }

    /**
     * The variable named by an "undeclared reference" error, when every error of the check is
     * one: the guard then reads a value the run does not have.
     */
    static String missingVariable(String errors) {
        String missing = null;
        for (String line : errors.split("\\R")) {
            int at = line.indexOf("undeclared reference to '");
            if (at >= 0) {
                int start = at + "undeclared reference to '".length();
                int end = line.indexOf('\'', start);
                if (missing == null && end > start) {
                    missing = line.substring(start, end);
                }
            } else if (line.startsWith("ERROR:")) {
                return null;
            }
        }
        return missing;
    }

    /** The run's parameters as CEL variables, with an alias for each name that has a dash. */
    static Map<String, Object> variables(Map<String, Object> parameters) {
        Map<String, Object> variables = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            if (name == null) {
                return;
            }
            Object converted = toCel(value);
            if (CEL_IDENTIFIER.matcher(name).matches()) {
                variables.put(name, converted);
            }
            String alias = name.replace('-', '_');
            if (!alias.equals(name) && CEL_IDENTIFIER.matcher(alias).matches()) {
                variables.putIfAbsent(alias, converted);
            }
        });
        return variables;
    }

    /** Converts a run value to the types CEL reads: maps, lists, long, double, string, bool. */
    static Object toCel(Object value) {
        if (value == null) {
            return NullValue.NULL_VALUE;
        }
        if (value instanceof JsonNode node) {
            return node.isNull() || node.isMissingNode() ? NullValue.NULL_VALUE
                    : toCel(IkanosMappers.json().convertValue(node, Object.class));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new HashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), toCel(v)));
            return converted;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> converted = new ArrayList<>();
            items.forEach(item -> converted.add(toCel(item)));
            return converted;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big) {
            return big.bitLength() < 64 ? (Object) big.longValue() : (Object) big.doubleValue();
        }
        if (value instanceof BigDecimal || value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    /** Reads a dotted path from the parameters, through maps and JSON objects. */
    static Object read(Map<String, Object> parameters, String path) {
        Object current = parameters;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof JsonNode node && node.isObject()) {
                current = node.get(segment);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** A value is present when it is neither absent, {@code null} nor {@code false}. */
    static boolean isPresent(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof JsonNode node) {
            return !node.isNull() && !node.isMissingNode()
                    && !(node.isBoolean() && !node.booleanValue());
        }
        return true;
    }

    private static String describe(Object result) {
        return result == null || result instanceof NullValue ? "null"
                : result.getClass().getSimpleName() + " " + result;
    }
}
