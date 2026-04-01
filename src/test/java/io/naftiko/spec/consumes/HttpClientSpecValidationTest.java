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
package io.naftiko.spec.consumes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class HttpClientSpecValidationTest {

    @Test
    public void constructorShouldRejectTrailingSlashInBaseUri() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new HttpClientSpec("api", "https://api.example.com/v1/", null));

        assertEquals("baseUri must not end with a trailing slash. Provided: 'https://api.example.com/v1/'",
                ex.getMessage());
    }

    @Test
    public void setterShouldRejectTrailingSlashInBaseUri() {
        HttpClientSpec spec = new HttpClientSpec();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> spec.setBaseUri("https://api.example.com/v1/"));

        assertEquals("baseUri must not end with a trailing slash. Provided: 'https://api.example.com/v1/'",
                ex.getMessage());
    }

    @Test
    public void constructorShouldAcceptBaseUriWithoutTrailingSlash() {
        assertDoesNotThrow(() -> {
            HttpClientSpec spec = new HttpClientSpec("api", "https://api.example.com/v1", null);
            assertEquals("https://api.example.com/v1", spec.getBaseUri());
        });
    }

    @Test
    public void setterShouldAcceptBaseUriWithoutTrailingSlash() {
        HttpClientSpec spec = new HttpClientSpec();

        assertDoesNotThrow(() -> spec.setBaseUri("https://api.example.com/v1"));
        assertEquals("https://api.example.com/v1", spec.getBaseUri());
    }
}
