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

import java.util.regex.Pattern;

/**
 * The placeholders of an exposed resource path, one rule for the REST adapter's routes and the
 * OpenAPI export. A placeholder is written {@code {{name}}}, as the schema documents; a name has
 * the characters of an input parameter name other than {@code *}: letters, digits, {@code _} and
 * {@code -}.
 */
public final class RestPaths {

    /** Characters of a placeholder name. */
    static final String NAME = "[A-Za-z0-9_-]+";

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{(" + NAME + ")\\}\\}");

    private RestPaths() {}

    /**
     * @param path a resource path with {@code {{name}}} placeholders, may be null
     * @return the same path as a URI template, with {@code {name}} placeholders
     */
    public static String toUriTemplate(String path) {
        return path == null ? null : MUSTACHE.matcher(path).replaceAll("{$1}");
    }
}
