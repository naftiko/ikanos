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

import java.util.HashSet;
import java.util.Set;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.ast.CelExpr;

/**
 * Collects the free identifiers of a parsed CEL expression: the top-level variables it reads.
 * The variables a comprehension binds ({@code x} in {@code items.all(x, x > 0)}) are not free
 * inside it.
 */
final class GuardIdentifiers {

    private GuardIdentifiers() {
    }

    static void collect(CelAbstractSyntaxTree ast, Set<String> names) {
        walk(ast.getExpr(), Set.of(), names);
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
