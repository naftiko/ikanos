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
package io.ikanos.spec.exposes.rest;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholders of an exposed resource path, one rule for the REST adapter's routes, the
 * OpenAPI export and the inputs a {@code ref} operation inherits (#1039). A placeholder is
 * written {@code {{name}}}, as the schema documents, or {@code {name}}, the URI template form;
 * a name has the characters of an input parameter name other than {@code *}: letters, digits,
 * {@code _} and {@code -}.
 */
public final class RestPaths {

    private static final String NAME = "[A-Za-z0-9_-]+";

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{(" + NAME + ")\\}\\}");

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{(" + NAME + ")\\}\\}|\\{(" + NAME + ")\\}");

    private RestPaths() {}

    /**
     * @param path a resource path with {@code {{name}}} placeholders
     * @return the same path as a URI template, with {@code {name}} placeholders
     */
    public static String toUriTemplate(String path) {
        return path == null ? null : MUSTACHE.matcher(path).replaceAll("{$1}");
    }

    /**
     * @param path a resource path, may be null
     * @return the names of its placeholders, in order
     */
    public static Set<String> placeholders(String path) {
        Set<String> names = new LinkedHashSet<>();
        if (path == null) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(path);
        while (matcher.find()) {
            names.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return names;
    }
}
