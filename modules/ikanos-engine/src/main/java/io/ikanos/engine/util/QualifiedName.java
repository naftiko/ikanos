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

/**
 * A {@code namespace.name} reference, as used by {@code call:} and {@code ref:}.
 *
 * <p>The reference is split on the <b>first</b> dot. Namespaces are kebab identifiers and never
 * contain a dot, but the name after it may: a consumed MCP tool is keyed by its upstream name,
 * which can contain dots and underscores ({@code fs.files.read} is namespace {@code fs}, tool
 * {@code files.read}). Every place that resolves a call or a ref uses this class so they all
 * agree on that split.</p>
 *
 * @param namespace the part before the first dot; never empty
 * @param name      the part after the first dot; never empty
 */
public record QualifiedName(String namespace, String name) {

    /**
     * Parse a qualified reference.
     *
     * @param reference the reference, e.g. {@code "billing.get-invoice"}
     * @return the parsed name, or {@code null} when {@code reference} is {@code null}, has no
     *         dot, or has nothing before or after the first dot
     */
    public static QualifiedName parse(String reference) {
        if (reference == null) {
            return null;
        }
        int dot = reference.indexOf('.');
        if (dot <= 0 || dot == reference.length() - 1) {
            return null;
        }
        return new QualifiedName(reference.substring(0, dot), reference.substring(dot + 1));
    }

    @Override
    public String toString() {
        return namespace + "." + name;
    }
}
