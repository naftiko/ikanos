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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The markers of structured JSON body data (#1132). They are keys of the authored YAML, read
 * before any key is resolved, and never sent:
 *
 * <ul>
 * <li>{@code $onlyIf: x} on an object: the object is sent only when {@code x} is present and not
 * {@code false}. {@code x} is looked up like the {@code x} of a {@code "{{x}}"} placeholder, so
 * an empty string, {@code 0} or the string {@code "false"} hold.</li>
 * <li>{@code $first: [variants]} as the only member of an object: the object is replaced by the
 * first variant whose {@code $onlyIf} holds, or that has none (the fallback), and is left out when
 * no variant applies, or when the chosen variant resolves to nothing (later variants are not
 * tried).</li>
 * <li>{@code $keep: true} on an object: the object is sent as {@code {}} when all its members are
 * left out, instead of being left out itself (#985).</li>
 * </ul>
 *
 * <p>A key written {@code $$...} is sent with one {@code $} less, so a payload can still carry a
 * member named {@code $onlyIf}. Any other key that starts with {@code $}, such as {@code $ref}, is
 * sent as written.</p>
 */
public final class JsonBodyMarkers {

    /** Sends the object only when the input it names is present and not {@code false}. */
    public static final String ONLY_IF = "$onlyIf";

    /** Takes the first applicable variant. */
    public static final String FIRST = "$first";

    /** Sends the object as {@code {}} when all its members are left out. */
    public static final String KEEP = "$keep";

    /** The prefix of a key that is sent with one {@code $} less. */
    private static final String ESCAPE = "$$";

    /**
     * The name an {@code $onlyIf} takes: what a {@code "{{name}}"} placeholder holds, a name or a
     * dotted path of non-empty segments.
     */
    private static final Pattern NAME =
            Pattern.compile("^[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*$");

    /**
     * A caller claim, named as the token writes it, so a namespaced claim such as
     * {@code https://example.com/role} or {@code cognito:groups} can be read too (#998).
     */
    private static final Pattern CLAIM = Pattern.compile("^auth\\.claims\\.[^\\s{}]+$");

    private JsonBodyMarkers() {
    }

    /** @return whether {@code text} is what an {@code $onlyIf} may name: an input or a claim */
    static boolean isName(String text) {
        return NAME.matcher(text).matches() || CLAIM.matcher(text).matches();
    }

    /** @return whether {@code key}, as authored, is one of the three markers */
    public static boolean isMarker(String key) {
        return ONLY_IF.equals(key) || FIRST.equals(key) || KEEP.equals(key);
    }

    /** @return {@code key} with the escape removed: {@code $$onlyIf} becomes {@code $onlyIf} */
    static String unescape(String key) {
        return key.startsWith(ESCAPE) ? key.substring(1) : key;
    }

    /** @return whether {@code object} is a {@code $first} choice */
    static boolean isChoice(Map<?, ?> object) {
        return object.containsKey(FIRST);
    }

    /** @return whether {@code object} is sent as {@code {}} when all its members are left out */
    static boolean keeps(Map<?, ?> object) {
        return Boolean.TRUE.equals(object.get(KEEP));
    }

    /**
     * @param object an object of structured body data, as authored
     * @param parameters the request parameters, may be null
     * @return {@code true} when {@code object} has no {@code $onlyIf}, or when the input it names
     *         is present and not {@code false}
     * @throws IllegalArgumentException when {@code $onlyIf} does not name an input; the load-time
     *         check ({@link #findErrors}) reports it first
     */
    static boolean applies(Map<?, ?> object, Map<String, Object> parameters) {
        if (!object.containsKey(ONLY_IF)) {
            return true;
        }
        Object name = object.get(ONLY_IF);
        if (!(name instanceof String text) || !isName(text)) {
            throw new IllegalArgumentException(
                    "$onlyIf in structured JSON body data must name an input");
        }
        // A caller claim is checked as in a placeholder: a missing claim fails the call (#998)
        Resolver.requireCallerValues("{{" + text + "}}", parameters);
        Object value = Resolver.lookupReference(text, parameters);
        return value != null && !Boolean.FALSE.equals(value);
    }

    /**
     * Checks the markers of structured body data, so a misused one fails when the capability
     * loads and in {@code ikanos validate}, not on a call.
     *
     * @param data the structured body data, as authored
     * @return one message per misused marker, each starting with the path of its object
     *         ({@code /} for the root)
     */
    public static List<String> findErrors(Object data) {
        List<String> errors = new ArrayList<>();
        check(data, "", errors);
        return errors;
    }

    private static void check(Object data, String path, List<String> errors) {
        if (data instanceof Map<?, ?> object) {
            String where = path.isEmpty() ? "/" : path;
            if (path.isEmpty() && object.containsKey(ONLY_IF)) {
                errors.add("/: $onlyIf cannot be on the root object, since the body is always sent;"
                        + " put it on a member");
            }
            checkObject(object, where, errors);
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (FIRST.equals(key) && entry.getValue() instanceof Collection<?> variants) {
                    int index = 0;
                    for (Object variant : variants) {
                        check(variant, path + "/" + FIRST + "/" + index++, errors);
                    }
                } else if (!isMarker(key)) {
                    check(entry.getValue(), path + "/" + key, errors);
                }
            }
        } else if (data instanceof Collection<?> items) {
            int index = 0;
            for (Object item : items) {
                check(item, path + "/" + index++, errors);
            }
        }
    }

    private static void checkObject(Map<?, ?> object, String where, List<String> errors) {
        if (object.containsKey(ONLY_IF) && !(object.get(ONLY_IF) instanceof String name
                && isName(name))) {
            errors.add(where + ": $onlyIf must name an input, without braces, such as"
                    + " '$onlyIf: silent'");
        }
        if (object.containsKey(KEEP) && !Boolean.TRUE.equals(object.get(KEEP))) {
            errors.add(where + ": $keep must be true; leave it out otherwise");
        }
        if (!object.containsKey(FIRST)) {
            return;
        }
        if (object.size() > 1) {
            errors.add(where + ": an object with $first has no other member; put $onlyIf, $keep"
                    + " and the members in its variants");
        }
        if (!(object.get(FIRST) instanceof Collection<?> variants) || variants.isEmpty()) {
            errors.add(where + ": $first must be a non-empty list of objects");
            return;
        }
        int index = 0;
        boolean fallbackSeen = false;
        for (Object variant : variants) {
            String at = where + (where.endsWith("/") ? "" : "/") + FIRST + "/" + index++;
            if (!(variant instanceof Map<?, ?> map)) {
                errors.add(at + ": a $first variant must be an object");
                continue;
            }
            if (fallbackSeen) {
                errors.add(at + ": never used, since a variant before it has no $onlyIf");
            }
            if (!map.containsKey(ONLY_IF)) {
                fallbackSeen = true;
            }
        }
    }

    /**
     * @param data the structured body data, as authored
     * @return the names the {@code $onlyIf} markers of {@code data} hold, in order, once each
     */
    public static Set<String> conditions(Object data) {
        Set<String> names = new LinkedHashSet<>();
        collect(data, names);
        return names;
    }

    private static void collect(Object data, Set<String> names) {
        if (data instanceof Map<?, ?> object) {
            if (object.get(ONLY_IF) instanceof String name) {
                names.add(name);
            }
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!ONLY_IF.equals(String.valueOf(entry.getKey()))) {
                    collect(entry.getValue(), names);
                }
            }
        } else if (data instanceof Collection<?> items) {
            for (Object item : items) {
                collect(item, names);
            }
        }
    }
}
