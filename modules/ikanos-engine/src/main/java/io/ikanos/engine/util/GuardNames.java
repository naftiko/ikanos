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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * How a guard names a declared name: the {@code $onlyIf} marker of a structured JSON body
 * (#1132) and the {@code onlyIf} of a step (#1167) follow the same rule, so they share it here.
 *
 * <ul>
 * <li>A dashed name is written in snake_case: {@code is_admin} reads an input {@code is-admin}.
 * The kebab form is refused, with the snake form as a hint.</li>
 * <li>Since a name can hold both {@code -} and {@code _}, {@code is_admin} could read an input
 * {@code is_admin} and an input {@code is-admin} at once. A name that matches more than one
 * declared name is refused, rather than one of them being picked.</li>
 * </ul>
 *
 * <p>The rule applies to the first segment of a dotted path only, the declared name. The other
 * segments are keys of its value, and a caller claim ({@code auth.claims.<name>}) is named as the
 * identity provider writes it.</p>
 */
public final class GuardNames {

    private GuardNames() {
    }

    /** @return {@code name} in snake_case: each {@code -} written {@code _} */
    public static String snake(String name) {
        return name.replace('-', '_');
    }

    /** @return the first segment of a dotted path: the declared name it reads */
    public static String head(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    /**
     * @param path a name or dotted path, as a guard writes it
     * @return the error for a declared name written with {@code -}, naming its snake form, or
     *         {@code null} when the first segment has none
     */
    public static String dashedNameError(String path) {
        String head = head(path);
        return head.indexOf('-') < 0 ? null
                : "'" + path + "' is written with '-': write it '" + snake(head)
                        + path.substring(head.length()) + "'";
    }

    /**
     * @param declared the names the guard can read
     * @param name the first segment of a name, as a guard writes it
     * @return the declared names {@code name} reads, sorted: the name itself, and each dashed
     *         name whose snake form it is. More than one is an ambiguity.
     */
    public static List<String> matches(Collection<String> declared, String name) {
        TreeSet<String> found = new TreeSet<>();
        if (declared == null || name == null) {
            return List.of();
        }
        if (name.indexOf('_') < 0) {
            // Only the name itself can match: no dashed name has it as its snake form
            return declared.contains(name) ? List.of(name) : List.of();
        }
        for (String candidate : declared) {
            if (candidate != null && (candidate.equals(name)
                    || candidate.indexOf('-') >= 0 && snake(candidate).equals(name))) {
                found.add(candidate);
            }
        }
        return List.copyOf(found);
    }

    /**
     * @param name the name, as a guard writes it
     * @param matches the declared names it matches, more than one
     * @return the error for a name that matches more than one declared name
     */
    public static String ambiguityError(String name, Collection<String> matches) {
        return "'" + name + "' matches more than one name (" + String.join(", ", matches)
                + "), since '-' is written '_': rename one of them";
    }

    /**
     * @param values the values a guard reads, by declared name; may be null
     * @param name the first segment of a name, as a guard writes it
     * @return the key of {@code values} that {@code name} reads, or {@code null} when none does
     * @throws IllegalArgumentException when {@code name} matches more than one key; the load
     *         refuses such a name first when the names are declared
     */
    public static String resolve(Map<String, ?> values, String name) {
        if (values == null) {
            return null;
        }
        List<String> found = matches(values.keySet(), name);
        if (found.size() > 1) {
            throw new IllegalArgumentException(ambiguityError(name, found));
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * @param values the values a guard reads, by declared name; may be null
     * @param name the first segment of a name, as a guard writes it
     * @return the value {@code name} reads, or {@code null} when none does
     * @throws IllegalArgumentException when {@code name} matches more than one key
     */
    public static Object lookup(Map<String, ?> values, String name) {
        String key = resolve(values, name);
        return key == null ? null : values.get(key);
    }
}
