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
package io.ikanos.engine.consumes.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import io.ikanos.engine.util.Resolver;

/**
 * Builds {@code application/x-www-form-urlencoded} request bodies ({@code type: formUrlEncoded}).
 *
 * <p>Both forms of {@code data} treat substituted values the same way: a value coming from a
 * {@code {{...}}} template is caller data, always percent-encoded (UTF-8) and never interpreted,
 * even when it itself contains braces. An unresolved template is rejected only when no parameters
 * are available, the one case where {@link Resolver#resolveMustacheTemplate} leaves templates in
 * place. With any parameter present, a misspelled or missing variable is rendered as an empty
 * value (JMustache {@code defaultValue("")}).</p>
 */
public final class FormUrlEncodedBody {

    private FormUrlEncodedBody() {
        // Utility class, no instantiation
    }

    /**
     * Encode a key/value form map. Mustache templates are resolved in each value first; keys and
     * values are then percent-encoded, so bracketed keys such as {@code line_items[0][quantity]}
     * are sent the way APIs like Stripe expect. Key order is preserved.
     *
     * @throws IllegalArgumentException when a value holds a template and no parameters are given
     */
    public static String encode(Map<?, ?> data, Map<String, Object> parameters) {
        boolean noParameters = parameters == null || parameters.isEmpty();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> entry : data.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String raw = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (noParameters && hasTemplate(raw)) {
                throw new IllegalArgumentException(
                        "Unresolved template parameters in form field '" + key + "': " + raw);
            }
            String value = Resolver.resolveMustacheTemplate(raw, parameters);
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encodeValue(key)).append('=').append(encodeValue(value));
        }
        return sb.toString();
    }

    /**
     * Resolve a pre-encoded form string. The author's literal text is already encoded and left
     * untouched; only substituted values are percent-encoded, so a caller value cannot add or
     * alter form fields. A template left in place (no parameters given) is caught by the caller's
     * unresolved-template guard.
     */
    public static String resolve(String template, Map<String, Object> parameters) {
        return Resolver.resolveMustacheTemplate(template, parameters,
                FormUrlEncodedBody::encodeValue);
    }

    static String encodeValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean hasTemplate(String value) {
        return value.contains("{{") && value.contains("}}");
    }
}
