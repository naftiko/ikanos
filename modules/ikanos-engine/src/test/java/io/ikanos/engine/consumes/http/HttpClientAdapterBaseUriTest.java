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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code baseUri} is resolved against the capability bindings once, at load time, and never from
 * call parameters (#745).
 */
class HttpClientAdapterBaseUriTest {

    @Test
    void resolveBaseUriShouldReturnLiteralUnchanged() {
        assertEquals("https://api.getlago.com/api/v1",
                HttpClientAdapter.resolveBaseUri("lago", "https://api.getlago.com/api/v1",
                        Map.of()));
    }

    @Test
    void resolveBaseUriShouldSubstituteBindings() {
        assertEquals("https://api.eu.getlago.com/api/v1",
                HttpClientAdapter.resolveBaseUri("lago", "https://{{LAGO_HOST}}/api/v1",
                        Map.of("LAGO_HOST", "api.eu.getlago.com")));
    }

    @Test
    void resolveBaseUriShouldAcceptWholeValueFromBinding() {
        assertEquals("http://localhost:3000/api/v1",
                HttpClientAdapter.resolveBaseUri("lago", "{{LAGO_API_URL}}",
                        Map.of("LAGO_API_URL", "http://localhost:3000/api/v1")));
    }

    @Test
    void resolveBaseUriShouldStripTrailingSlashFromBoundValue() {
        // The baseUri-no-trailing-slash lint rule only sees {{API_URL}}, so a bound value
        // ending in / would otherwise give // once a resource path is appended.
        assertEquals("https://api.example.com/v1",
                HttpClientAdapter.resolveBaseUri("api", "{{API_URL}}",
                        Map.of("API_URL", "https://api.example.com/v1/")));
    }

    @Test
    void resolveBaseUriShouldNotEncodeBoundValue() {
        assertEquals("https://host.test/a/b?x=1",
                HttpClientAdapter.resolveBaseUri("api", "{{URL}}",
                        Map.of("URL", "https://host.test/a/b?x=1")));
    }

    @Test
    void resolveBaseUriShouldFailWhenPlaceholderIsNotBound() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HttpClientAdapter.resolveBaseUri("lago", "https://{{LAGO_HOST}}/api/v1",
                        Map.of("OTHER", "x")));
        assertTrue(error.getMessage().contains("lago"), error.getMessage());
        assertTrue(error.getMessage().contains("LAGO_HOST"), error.getMessage());
    }

    @Test
    void resolveBaseUriShouldFailWhenBindingsAreNull() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpClientAdapter.resolveBaseUri("lago", "https://{{LAGO_HOST}}", null));
    }

    @Test
    void resolveBaseUriShouldFailWhenResolvedValueIsNotHttp() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HttpClientAdapter.resolveBaseUri("lago", "{{URL}}",
                        Map.of("URL", "file:///etc/passwd")));
        assertFalse(error.getMessage().contains("/etc/passwd"),
                "the resolved value may be secret and must not be echoed: " + error.getMessage());
    }

    @Test
    void resolveBaseUriShouldFailWhenResolvedValueHasNoHost() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpClientAdapter.resolveBaseUri("lago", "https://{{HOST}}/v1",
                        Map.of("HOST", "")));
    }

    @Test
    void resolveBaseUriShouldKeepNullAndBlank() {
        assertEquals(null, HttpClientAdapter.resolveBaseUri("api", null, Map.of()));
        assertEquals("", HttpClientAdapter.resolveBaseUri("api", "", Map.of()));
    }
}
