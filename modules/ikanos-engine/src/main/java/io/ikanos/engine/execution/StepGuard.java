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
import dev.cel.common.CelErrorCode;
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
import io.ikanos.engine.util.CallerRoot;
import io.ikanos.engine.util.Resolver;
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
 * ({@code get_ship}). An expression that names a variable the run does not have, or a field
 * its value does not have, does not hold either.</li>
 * </ul>
 *
 * <p>A guard that reads the caller's claims ({@code auth}) fails when the run has none. Apart
 * from those, the guard only reads values the run already holds, so it gives the same answer
 * when a durable run is replayed from its journal.</p>
 */
public final class StepGuard {

    /**
     * A bare name: dotted segments looked up like the {@code x} of a {@code "{{x}}"}
     * placeholder, as the {@code $onlyIf} marker of a structured JSON body does. A segment may
     * start with a digit and hold {@code -} and {@code :}, as in
     * {@code auth.claims.cognito:groups}.
     */
    private static final Pattern BARE_NAME =
            Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:-]*(\\.[A-Za-z0-9_][A-Za-z0-9_:-]*)*");
    /** A number, which a bare name never is: {@code 1} or {@code 1.5} are CEL literals. */
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");
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

    /** Compiled guards, by expression: each is checked against the names it reads. */
    private static final Map<String, Compiled> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 1024;

    private StepGuard() {
    }

    /** A guard compiled against the top-level names it reads, all declared as {@code dyn}. */
    private record Compiled(CelRuntime.Program program, Set<String> names) {
    }

    /**
     * @param onlyIf the guard
     * @return whether the guard is a bare name, tested for presence, rather than a CEL expression
     */
    public static boolean isBareName(String onlyIf) {
        String guard = onlyIf.strip();
        return BARE_NAME.matcher(guard).matches() && !CEL_WORDS.contains(guard)
                && !NUMBER.matcher(guard).matches();
    }

    /**
     * Whether a step with this guard runs.
     *
     * @param step the step name, for the error message
     * @param onlyIf the guard, or {@code null} when the step has none
     * @param parameters the values the run holds: inputs, {@code with} values, step outputs
     * @return {@code true} when the step runs
     * @throws IllegalArgumentException when the guard is not a valid CEL expression, fails to
     *         evaluate, does not evaluate to a boolean, or reads the caller's claims in a run
     *         that has none
     */
    public static boolean holds(String step, String onlyIf, Map<String, Object> parameters) {
        return evaluate("The onlyIf of step '" + step + "'", onlyIf, parameters);
    }

    private static boolean evaluate(String label, String onlyIf, Map<String, Object> parameters) {
        if (onlyIf == null || onlyIf.isBlank()) {
            return true;
        }
        if (isBareName(onlyIf)) {
            String name = onlyIf.strip();
            requireCallerRoot(label, Set.of(firstSegment(name)), parameters);
            return isPresent(read(parameters, name));
        }
        Compiled compiled = compile(label, onlyIf);
        requireCallerRoot(label, compiled.names(), parameters);
        Map<String, Object> variables = new LinkedHashMap<>();
        for (String name : compiled.names()) {
            Object value = lookup(parameters, name);
            if (value == null) {
                // The guard reads a value the run does not have: it does not hold, as for a
                // bare name.
                return false;
            }
            variables.put(name, toCel(value));
        }
        Object result;
        try {
            result = compiled.program().eval(variables);
        } catch (CelEvaluationException e) {
            if (e.getErrorCode() == CelErrorCode.ATTRIBUTE_NOT_FOUND) {
                // A field the value does not have, such as order.missing: absent, as above.
                return false;
            }
            throw new IllegalArgumentException(label + " could not be evaluated: "
                    + e.getMessage(), e);
        }
        if (!(result instanceof Boolean value)) {
            throw new IllegalArgumentException(label + " must evaluate to true or false, not "
                    + describe(result) + ".");
        }
        return value;
    }

    /**
     * A guard that reads the caller's claims fails, as a template does, when the run has no
     * caller root: the request carried no validated token, or the run is a task resumed after a
     * restart, whose caller claims were never journaled. Skipping the step instead would let a
     * resumed task end as a success without it.
     */
    private static void requireCallerRoot(String label, Set<String> names,
            Map<String, Object> parameters) {
        if (names.contains(Resolver.CALLER_ROOT)
                && !(parameters.get(Resolver.CALLER_ROOT) instanceof CallerRoot)) {
            throw new IllegalArgumentException(label + " reads the caller's claims ('"
                    + Resolver.CALLER_ROOT + "'), which this run does not have: the request"
                    + " carried no validated token, or the run resumed after a restart");
        }
    }

    /**
     * Checks that a guard can be read, without evaluating it: that it parses, and that each
     * function it calls exists.
     *
     * @return the error, or {@code null} when the guard is a bare name or a valid CEL expression
     */
    public static String parseError(String onlyIf) {
        if (onlyIf == null || onlyIf.isBlank() || isBareName(onlyIf)) {
            return null;
        }
        String prefix = "The guard is not a valid CEL expression: ";
        try {
            compile("The guard", onlyIf);
            return null;
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
        }
    }

    /**
     * The top-level names a guard reads: the first segment of a bare name, or the free
     * identifiers of a CEL expression (comprehension variables excluded).
     *
     * @return the names, or an empty set when the guard cannot be parsed
     */
    public static Set<String> referencedNames(String onlyIf) {
        Set<String> names = new TreeSet<>();
        if (onlyIf == null || onlyIf.isBlank()) {
            return names;
        }
        if (isBareName(onlyIf)) {
            names.add(firstSegment(onlyIf.strip()));
            return names;
        }
        CelValidationResult parsed = PARSER.parse(onlyIf);
        if (parsed.hasError()) {
            return names;
        }
        try {
            GuardIdentifiers.collect(parsed.getAst(), names);
        } catch (CelValidationException e) {
            return new TreeSet<>();
        }
        return names;
    }

    /**
     * The subtractions of names in a CEL guard that could be a dashed name written with its dash,
     * each as the list of names subtracted ({@code [get, ship]} for {@code get-ship.count > 0}).
     *
     * @return the subtractions, or an empty list for a bare name or a guard that does not parse
     */
    public static List<List<String>> subtractedNames(String onlyIf) {
        if (onlyIf == null || onlyIf.isBlank() || isBareName(onlyIf)) {
            return List.of();
        }
        CelValidationResult parsed = PARSER.parse(onlyIf);
        if (parsed.hasError()) {
            return List.of();
        }
        try {
            return GuardIdentifiers.subtractions(parsed.getAst());
        } catch (CelValidationException e) {
            return List.of();
        }
    }

    private static String firstSegment(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Compiles a guard with each top-level name it reads declared as {@code dyn}, so a typo in a
     * function or method name, such as {@code sizee(items)}, is an error rather than a guard
     * that never holds.
     */
    private static Compiled compile(String label, String onlyIf) {
        Compiled cached = CACHE.get(onlyIf);
        if (cached != null) {
            return cached;
        }
        CelValidationResult parsed = PARSER.parse(onlyIf);
        if (parsed.hasError()) {
            throw new IllegalArgumentException(label + " is not a valid CEL expression: "
                    + parsed.getErrorString());
        }
        Set<String> names = new TreeSet<>();
        try {
            GuardIdentifiers.collect(parsed.getAst(), names);
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(label + " is not a valid CEL expression: "
                    + e.getMessage(), e);
        }
        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(OPTIONS)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        for (String name : names) {
            builder.addVar(name, SimpleType.DYN);
        }
        CelValidationResult checked = builder.build().compile(onlyIf);
        if (checked.hasError()) {
            throw new IllegalArgumentException(label + " is not a valid CEL expression: "
                    + checked.getErrorString());
        }
        Compiled compiled;
        try {
            compiled = new Compiled(RUNTIME.createProgram(checked.getAst()), Set.copyOf(names));
        } catch (CelValidationException | CelEvaluationException e) {
            throw new IllegalArgumentException(label + " is not a valid CEL expression: "
                    + e.getMessage(), e);
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(onlyIf, compiled);
        return compiled;
    }

    /**
     * The value of a top-level CEL name: the parameter of that name, or, for a name with an
     * underscore that is no parameter, the parameter whose dashes it stands for
     * ({@code get_ship} for {@code get-ship}).
     */
    static Object lookup(Map<String, Object> parameters, String name) {
        if (parameters.containsKey(name)) {
            return parameters.get(name);
        }
        if (name.indexOf('_') >= 0) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (entry.getKey() != null && entry.getKey().indexOf('-') >= 0
                        && entry.getKey().replace('-', '_').equals(name)) {
                    return entry.getValue();
                }
            }
        }
        return null;
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
