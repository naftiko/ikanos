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
package io.naftiko.engine.exposes;

import java.util.Set;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

/**
 * AST expression checker that blocks dangerous dynamic method calls in Groovy scripts.
 *
 * <p>{@link SecureASTCustomizer}'s receiver list only catches statically-typed receivers resolved
 * at compile time. This checker supplements it by blocking method names that enable runtime
 * escapes regardless of the receiver type:</p>
 * <ul>
 *   <li>{@code execute} — Groovy GDK adds {@code String.execute()} for process execution</li>
 *   <li>{@code getClass} — returns a {@code Class} object, opening reflection access</li>
 *   <li>{@code forName}, {@code getDeclaredMethod}, {@code getDeclaredField},
 *       {@code getDeclaredConstructor} — {@code Class} reflection methods</li>
 *   <li>{@code newInstance} — reflective instantiation</li>
 *   <li>{@code setAccessible} — disables access checks on reflected members</li>
 * </ul>
 */
class GroovySandboxExpressionChecker implements SecureASTCustomizer.ExpressionChecker {

        private static final Set<String> BLOCKED_METHODS = Set.of(
            "execute",
            "getClass",
            "forName",
            "getDeclaredMethod",
            "getDeclaredMethods",
            "getDeclaredField",
            "getDeclaredFields",
            "getDeclaredConstructor",
            "getDeclaredConstructors",
            "getMethod",
            "getMethods",
            "getField",
            "getFields",
            "getConstructor",
            "getConstructors",
            "newInstance",
            "setAccessible",
            "invokeMethod",
            "getMetaClass",
            "setMetaClass"
        );

    private static final Set<String> BLOCKED_PROPERTIES = Set.of(
            "class"
    );

    /** Class names blocked in constructor calls and class expressions. */
    private static final Set<String> BLOCKED_CLASSES = Set.of(
            "ProcessBuilder", "java.lang.ProcessBuilder",
            "Thread", "java.lang.Thread",
            "ThreadGroup", "java.lang.ThreadGroup",
            "Runtime", "java.lang.Runtime",
            "ClassLoader", "java.lang.ClassLoader",
            "GroovyShell", "groovy.lang.GroovyShell",
            "GroovyClassLoader", "groovy.lang.GroovyClassLoader"
    );

    @Override
    public boolean isAuthorized(Expression expression) {
        if (expression instanceof MethodCallExpression methodCall) {
            String methodName = methodCall.getMethodAsString();
            if (methodName != null && BLOCKED_METHODS.contains(methodName)) {
                return false;
            }
        }
        if (expression instanceof PropertyExpression propertyExpr) {
            String property = propertyExpr.getPropertyAsString();
            if (property != null && BLOCKED_PROPERTIES.contains(property)) {
                return false;
            }
            // Block property access on blocked class receivers (e.g. Runtime.class)
            Expression objectExpr = propertyExpr.getObjectExpression();
            if (objectExpr instanceof ClassExpression classExpr) {
                String className = classExpr.getType().getName();
                if (BLOCKED_CLASSES.contains(className)) {
                    return false;
                }
            }
        }
        if (expression instanceof ConstructorCallExpression ctorCall) {
            String typeName = ctorCall.getType().getName();
            if (BLOCKED_CLASSES.contains(typeName)) {
                return false;
            }
        }
        if (expression instanceof ClassExpression classExpr) {
            String className = classExpr.getType().getName();
            if (BLOCKED_CLASSES.contains(className)) {
                return false;
            }
        }
        return true;
    }
}
