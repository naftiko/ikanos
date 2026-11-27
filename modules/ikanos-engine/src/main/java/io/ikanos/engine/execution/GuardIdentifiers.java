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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.ast.CelExpr;

/**
 * Collects the free identifiers of a parsed CEL expression: the top-level variables it reads.
 * The variables a comprehension binds ({@code x} in {@code items.all(x, x > 0)}) are not free
 * inside it.
 */
final class GuardIdentifiers {

    /** CEL's name for the binary minus operator. */
    private static final String SUBTRACT = "_-_";
    /** CEL's names for logical not, numeric negation and indexing. */
    private static final String NOT = "!_";
    private static final String NEGATE = "-_";
    private static final String INDEX = "_[_]";

    private GuardIdentifiers() {
    }

    static void collect(CelAbstractSyntaxTree ast, Set<String> names) {
        walk(ast.getExpr(), Set.of(), names);
    }

    /**
     * The subtractions of names that could be a dashed name written with its dash: {@code
     * get-ship.count} parses as {@code get - ship.count} and gives {@code [get, ship]}, {@code
     * a-b-c} gives {@code [a, b, c]} (and {@code [a, b]} for its inner subtraction). Each list is
     * the names in order, the right one cut at its first dot.
     */
    static List<List<String>> subtractions(CelAbstractSyntaxTree ast) {
        return located(ast).stream().map(Subtraction::parts).toList();
    }

    /** A subtraction of names, and the offset of its minus in the expression, or -1. */
    record Subtraction(List<String> parts, int offset) {
    }

    /** The subtractions of {@link #subtractions}, each with where its minus is. */
    static List<Subtraction> located(CelAbstractSyntaxTree ast) {
        List<Found> found = new ArrayList<>();
        subtractions(ast.getExpr(), found);
        Map<Long, Integer> positions = ast.getSource().getPositionsMap();
        return found.stream()
                .map(f -> new Subtraction(f.parts(), positions.getOrDefault(f.id(), -1)))
                .toList();
    }

    /** A subtraction of names and the id of its call, whose position the source records. */
    private record Found(List<String> parts, long id) {
    }

    private static void subtractions(CelExpr expr, List<Found> found) {
        switch (expr.getKind()) {
            case CALL -> {
                if (isSubtraction(expr)) {
                    List<String> left = dashedParts(expr.call().args().get(0));
                    String right = headName(expr.call().args().get(1));
                    if (left != null && right != null) {
                        List<String> parts = new ArrayList<>(left);
                        parts.add(right);
                        found.add(new Found(List.copyOf(parts), expr.id()));
                    }
                }
                expr.call().target().ifPresent(target -> subtractions(target, found));
                expr.call().args().forEach(arg -> subtractions(arg, found));
            }
            case SELECT -> subtractions(expr.select().operand(), found);
            case LIST -> expr.list().elements().forEach(e -> subtractions(e, found));
            case MAP -> expr.map().entries().forEach(entry -> {
                subtractions(entry.key(), found);
                subtractions(entry.value(), found);
            });
            case STRUCT -> expr.struct().entries().forEach(e -> subtractions(e.value(), found));
            case COMPREHENSION -> {
                CelExpr.CelComprehension loop = expr.comprehension();
                subtractions(loop.iterRange(), found);
                subtractions(loop.loopStep(), found);
                subtractions(loop.result(), found);
            }
            default -> {
                // constants and identifiers hold no subtraction
            }
        }
    }

    private static boolean isSubtraction(CelExpr expr) {
        return SUBTRACT.equals(expr.call().function()) && expr.call().target().isEmpty()
                && expr.call().args().size() == 2;
    }

    /**
     * {@code a} gives {@code [a]}, {@code a - b} gives {@code [a, b]}, and a negation reads
     * through: {@code !get-ship.active} parses as {@code (!get) - ship.active}, so {@code !a}
     * and {@code -a} give {@code [a]}; anything else null.
     */
    private static List<String> dashedParts(CelExpr expr) {
        if (expr.getKind() == CelExpr.ExprKind.Kind.IDENT) {
            return List.of(expr.ident().name());
        }
        if (expr.getKind() == CelExpr.ExprKind.Kind.CALL && expr.call().target().isEmpty()
                && expr.call().args().size() == 1
                && (NOT.equals(expr.call().function())
                        || NEGATE.equals(expr.call().function()))) {
            return dashedParts(expr.call().args().get(0));
        }
        if (expr.getKind() == CelExpr.ExprKind.Kind.CALL && isSubtraction(expr)
                && expr.call().args().get(1).getKind() == CelExpr.ExprKind.Kind.IDENT) {
            List<String> left = dashedParts(expr.call().args().get(0));
            if (left != null) {
                List<String> parts = new ArrayList<>(left);
                parts.add(expr.call().args().get(1).ident().name());
                return parts;
            }
        }
        return null;
    }

    /**
     * The name a path starts with: {@code ship} for {@code ship.count}, {@code ship.crew.size()},
     * {@code ship.crew.filter(c, c.active)}, {@code ship['count']} or {@code ship.crew[0]}; null
     * if none.
     */
    private static String headName(CelExpr expr) {
        CelExpr current = expr;
        while (true) {
            switch (current.getKind()) {
                case IDENT -> {
                    return current.ident().name();
                }
                case SELECT -> current = current.select().operand();
                case CALL -> {
                    if (current.call().target().isPresent()) {
                        current = current.call().target().get();
                    } else if (INDEX.equals(current.call().function())
                            && !current.call().args().isEmpty()) {
                        current = current.call().args().get(0);
                    } else {
                        return null;
                    }
                }
                // a macro such as filter or all, expanded over the list it reads
                case COMPREHENSION -> current = current.comprehension().iterRange();
                default -> {
                    return null;
                }
            }
        }
    }

    private static void walk(CelExpr expr, Set<String> bound, Set<String> names) {
        switch (expr.getKind()) {
            case IDENT -> {
                String name = expr.ident().name();
                if (!bound.contains(name)) {
                    names.add(name);
                }
            }
            case SELECT -> walk(expr.select().operand(), bound, names);
            case CALL -> {
                expr.call().target().ifPresent(target -> walk(target, bound, names));
                expr.call().args().forEach(arg -> walk(arg, bound, names));
            }
            case LIST -> expr.list().elements().forEach(e -> walk(e, bound, names));
            case MAP -> expr.map().entries().forEach(entry -> {
                walk(entry.key(), bound, names);
                walk(entry.value(), bound, names);
            });
            case STRUCT -> expr.struct().entries().forEach(e -> walk(e.value(), bound, names));
            case COMPREHENSION -> {
                CelExpr.CelComprehension loop = expr.comprehension();
                walk(loop.iterRange(), bound, names);
                walk(loop.accuInit(), bound, names);
                Set<String> inner = new HashSet<>(bound);
                inner.add(loop.iterVar());
                if (!loop.iterVar2().isEmpty()) {
                    inner.add(loop.iterVar2());
                }
                inner.add(loop.accuVar());
                walk(loop.loopCondition(), inner, names);
                walk(loop.loopStep(), inner, names);
                walk(loop.result(), inner, names);
            }
            default -> {
                // constants and unset nodes read nothing
            }
        }
    }
}
