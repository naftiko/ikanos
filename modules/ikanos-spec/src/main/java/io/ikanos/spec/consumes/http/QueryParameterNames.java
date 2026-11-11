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
package io.ikanos.spec.consumes.http;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.ikanos.spec.InputParameterSpec;

/**
 * The {@code queryParameters} list of a consumed HTTP resource or operation (#987): names of query
 * parameters that only declare their location.
 *
 * <p>Each name is expanded, when the spec is read, into the {@link InputParameterSpec} that an
 * {@code inputParameters} entry with only {@code in: "query"} deserializes to, appended after the
 * declared entries. The request path, the load-time checks and every other reader of
 * {@code getInputParameters()} therefore see the same thing as the long form.</p>
 */
public final class QueryParameterNames {

    private QueryParameterNames() {
    }

    /**
     * The declared input parameters followed by one {@code in: query} parameter per listed name.
     * A name already declared, or listed twice, is expanded once at most and kept as declared;
     * {@link #errors} reports it.
     *
     * @param declared the {@code inputParameters} entries, keyed by name, in declaration order
     * @param names the {@code queryParameters} list, may be empty
     * @return a new map, in declaration order
     */
    public static Map<String, InputParameterSpec> expand(Map<String, InputParameterSpec> declared,
            List<String> names) {
        Map<String, InputParameterSpec> expanded = new LinkedHashMap<>(declared);
        for (String name : names) {
            if (name != null && !expanded.containsKey(name)) {
                expanded.put(name, locationOnlyQueryParameter(name));
            }
        }
        return expanded;
    }

    /**
     * The parameter an {@code inputParameters} entry {@code name: {in: "query"}} deserializes to.
     */
    public static InputParameterSpec locationOnlyQueryParameter(String name) {
        InputParameterSpec parameter = new InputParameterSpec();
        parameter.setName(name);
        parameter.setIn("query");
        return parameter;
    }

    /**
     * @param declared the {@code inputParameters} entries of the same resource or operation
     * @param names the {@code queryParameters} list
     * @param where the resource or operation, for the message
     * @return one message per name also declared in {@code inputParameters} or listed twice
     */
    public static List<String> errors(Map<String, InputParameterSpec> declared, List<String> names,
            String where) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                errors.add(where + " lists query parameter '" + name
                        + "' twice in queryParameters");
            } else if (declared.containsKey(name)) {
                errors.add(where + " declares '" + name
                        + "' in both inputParameters and queryParameters");
            }
        }
        return errors;
    }
}
