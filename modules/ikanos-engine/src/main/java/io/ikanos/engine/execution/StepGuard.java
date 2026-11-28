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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationListener;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.common.values.NullValue;
import io.ikanos.engine.util.CallerRoot;
import io.ikanos.engine.util.Resolver;
import io.ikanos.spec.exposes.control.GuardManagementSpec;
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
    /** A dashed name inside has(), which CEL refuses before any subtraction is read. */
    private static final Pattern HAS_DASHED_NAME =
            Pattern.compile("has\\(\\s*([A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z_][A-Za-z0-9_]*)+)");
    /** Where CEL refuses a subtraction: {@code <input>:1:27: found no matching overload ...}. */
    private static final Pattern SUBTRACTION_ERROR =
            Pattern.compile("(\\d+):(\\d+): found no matching overload for '_-_'");
    /** A number, which a bare name never is: {@code 1} or {@code 1.5} are CEL literals. */
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    /** Words a bare name never is: CEL reads them as literals or reserves them. */
    private static final Set<String> CEL_WORDS = Set.of("true", "false", "null", "in", "as",
            "break", "const", "continue", "else", "for", "function", "if", "import", "let",
            "loop", "package", "namespace", "return", "var", "void", "while");

    /**
     * The most bytes of a compiled regular expression ({@code matches}), so a pattern cannot
     * make the matcher itself costly.
     */
    static final int MAX_REGEX_PROGRAM_SIZE = 1_000;

    private static final CelOptions OPTIONS = CelOptions.current()
            .enableHeterogeneousNumericComparisons(true)
            // No iteration cap of CEL's own: the evaluation budget counts every iteration of
            // every macro, and management.guards.evaluationLimit raises it.
            .maxRegexProgramSize(MAX_REGEX_PROGRAM_SIZE)
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

    /**
     * A guard compiled against the top-level names it reads, all declared as {@code dyn}, with
     * the ids of the operands whose list an operator walks in one step.
     */
    private record Compiled(CelRuntime.Program program, Set<String> names, Set<Long> walked) {
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
        return holds(step, onlyIf, parameters, GuardManagementSpec.DEFAULT_EVALUATION_LIMIT);
    }

    /**
     * Whether a step with this guard runs, evaluating at most {@code evaluationLimit}
     * sub-expressions.
     *
     * @throws IllegalArgumentException as {@link #holds(String, String, Map)}, and when the
     *         guard evaluates more than {@code evaluationLimit} sub-expressions
     */
    public static boolean holds(String step, String onlyIf, Map<String, Object> parameters,
            long evaluationLimit) {
        return evaluate("The onlyIf of step '" + step + "'", onlyIf, parameters,
                evaluationLimit);
    }

    private static boolean evaluate(String label, String onlyIf, Map<String, Object> parameters,
            long evaluationLimit) {
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
            result = compiled.program().trace(variables, new Budget(label, evaluationLimit, compiled.walked()));
        } catch (BudgetExceeded e) {
            throw e.failure();
        } catch (CelEvaluationException e) {
            BudgetExceeded exceeded = budgetExceeded(e);
            if (exceeded != null) {
                throw exceeded.failure();
            }
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
     * Counts the work of an evaluation and stops it past the limit: one for each sub-expression
     * evaluated, each iteration of a macro included, plus the length of each list that an
     * {@code in}, {@code ==}, {@code !=} or {@code +} walks in one step. Reading a field of a
     * map walks nothing, and a macro's iterations already count, so neither is charged more.
     */
    private static final class Budget implements CelEvaluationListener {
        private final String label;
        private final long limit;
        private final Set<Long> walked;
        private long used;

        Budget(String label, long limit, Set<Long> walked) {
            this.label = label;
            this.limit = limit;
            this.walked = walked;
        }

        @Override
        public void callback(CelExpr expr, Object result) {
            used += 1 + (walked.contains(expr.id()) ? sizeOf(result) : 0);
            if (used > limit) {
                throw new BudgetExceeded(new IllegalArgumentException(label
                        + " evaluated more than " + limit + " sub-expressions and items, the limit"
                        + " set by management.guards.evaluationLimit on the control port: make"
                        + " it cheaper, such as by not reading a long list inside a macro over"
                        + " another"));
            }
        }
    }

    /** The number of items of a list, or 0 for any other value. */
    static long sizeOf(Object value) {
        return value instanceof java.util.Collection<?> items ? items.size() : 0;
    }

    /** Stops an evaluation past its budget, carrying the failure to report. */
    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient IllegalArgumentException failure;

        BudgetExceeded(IllegalArgumentException failure) {
            super(failure.getMessage(), null, false, false);
            this.failure = failure;
        }

        IllegalArgumentException failure() {
            return failure;
        }
    }

    private static BudgetExceeded budgetExceeded(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BudgetExceeded exceeded) {
                return exceeded;
            }
        }
        return null;
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
            String error = message.startsWith(prefix) ? message.substring(prefix.length())
                    : message;
            return error + dashHint(onlyIf, error);
        }
    }

    /**
     * A hint, on a line of its own, for an expression that does not compile because it subtracts
     * names, as a dashed name written with its dash does ({@code list-merged.filter(...)}); an
     * empty string when the error is not about a subtraction or the expression subtracts no
     * names.
     */
    static String dashHint(String expression, String error) {
        if (error == null) {
            return "";
        }
        if (error.contains("has() macro")) {
            // has(get-ship.status) does not parse, so there is no subtraction to find
            Matcher dashed = HAS_DASHED_NAME.matcher(expression);
            return dashed.find() ? hint(List.of(dashed.group(1).split("-"))) : "";
        }
        Matcher at = SUBTRACTION_ERROR.matcher(error);
        if (!at.find()) {
            return "";
        }
        CelValidationResult parsed = PARSER.parse(expression);
        if (parsed.hasError()) {
            return "";
        }
        List<GuardIdentifiers.Subtraction> subtractions;
        try {
            subtractions = GuardIdentifiers.located(parsed.getAst());
        } catch (CelValidationException e) {
            return "";
        }
        if (subtractions.isEmpty()) {
            return "";
        }
        // The subtraction CEL refused, at the line and column of its error
        int offset = offsetOf(expression, Integer.parseInt(at.group(1)),
                Integer.parseInt(at.group(2)));
        for (GuardIdentifiers.Subtraction subtraction : subtractions) {
            if (subtraction.offset() == offset) {
                return hint(subtraction.parts());
            }
        }
        // CEL refused a subtraction that is not of names: naming another one would mislead
        return "";
    }

    private static String hint(List<String> parts) {
        return "\n'-' is minus in CEL: if '" + String.join("-", parts) + "' is a name, write it '"
                + String.join("_", parts) + "'";
    }

    /** The offset of a 1-based line and column, as CEL errors print them, in the expression. */
    static int offsetOf(String expression, int line, int column) {
        int offset = 0;
        for (int current = 1; current < line; current++) {
            int next = expression.indexOf(10, offset); // a line feed
            if (next < 0) {
                return -1;
            }
            offset = next + 1;
        }
        return offset + column - 1;
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
            compiled = new Compiled(RUNTIME.createProgram(checked.getAst()), Set.copyOf(names),
                    GuardIdentifiers.walkedOperands(checked.getAst().getExpr()));
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
        boolean top = true;
        for (String segment : path.split("\\.")) {
            if (top) {
                // The top-level name resolves as in a CEL guard: get_ship reads get-ship
                current = lookup(parameters, segment);
                top = false;
            } else if (current instanceof Map<?, ?> map) {
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
