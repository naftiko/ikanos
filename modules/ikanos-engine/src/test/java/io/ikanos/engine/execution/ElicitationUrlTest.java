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
package io.ikanos.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.ikanos.spec.util.OperationStepElicitSpec;

/** The URL a URL mode {@code elicit} step sends its caller to (issue 1151). */
class ElicitationUrlTest {

    private static OperationStepElicitSpec step(String url) {
        OperationStepElicitSpec step = new OperationStepElicitSpec("payment", null, null);
        step.setMode(OperationStepElicitSpec.URL);
        step.setUrl(url);
        return step;
    }

    @Test
    void urlShouldEncodeAQueryValueSoItAddsNoParameter() {
        String url = Elicitation.url(step("https://billing.example.com/setup?plan={{plan}}"),
                Map.of("plan", "pro&admin=true"));

        assertEquals("https://billing.example.com/setup?plan=pro%26admin%3Dtrue", url);
    }

    @Test
    void urlShouldEncodeAPathValueSoItStaysOneSegment() {
        String url = Elicitation.url(step("https://billing.example.com/{{tenant}}/setup"),
                Map.of("tenant", "acme/../admin"));

        assertEquals("https://billing.example.com/acme%2F..%2Fadmin/setup", url);
    }

    @Test
    void urlShouldEncodeAValueWithASpaceInsteadOfFailing() {
        String url = Elicitation.url(step("https://billing.example.com/setup?name={{name}}"),
                Map.of("name", "Ada Lovelace"));

        assertEquals("https://billing.example.com/setup?name=Ada%20Lovelace", url);
    }

    @Test
    void urlShouldRefuseAPlainHttpUrlOutsideLocalhost() {
        assertThrows(IllegalStateException.class,
                () -> Elicitation.url(step("http://billing.example.com/setup"), Map.of()));
    }

    @Test
    void urlShouldAcceptPlainHttpOnLocalhost() {
        assertEquals("http://localhost:8080/setup",
                Elicitation.url(step("http://localhost:8080/setup"), Map.of()));
    }
}
