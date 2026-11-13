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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * A static consumed header value the engine can never send fails at load time and in
 * {@code ikanos validate}, instead of failing or being dropped on every call (#1001).
 */
class ConsumedHeaderValidatorTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void findErrorsShouldBeEmptyWhenSpecIsNull() {
        assertTrue(ConsumedHeaderValidator.findErrors(null).isEmpty());
    }

    @Test
    void findErrorsShouldNameAdapterHeaderWhenStaticValueCannotBeParsed() throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("""
                      inputParameters:
                        If-Match:
                          in: header
                          value: "v1"
                """, "", ""));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("If-Match"), errors.get(0));
        assertFalse(errors.get(0).contains("v1"), "the error must not echo the value");
    }

    @Test
    void findErrorsShouldNameResourceHeaderWhenStaticValueCannotBeParsed() throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("", """
                          inputParameters:
                            Range:
                              in: header
                              value: "items=0-9"
                """, ""));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("resource 'items'"), errors.get(0));
        assertTrue(errors.get(0).contains("Range"), errors.get(0));
        assertFalse(errors.get(0).contains("items=0-9"), "the error must not echo the value");
    }

    @Test
    void findErrorsShouldNameOperationHeaderWhenStaticValueCannotBeParsed() throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("", "", """
                              inputParameters:
                                If-Unmodified-Since:
                                  in: header
                                  value: "last tuesday"
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("up.list"), errors.get(0));
        assertFalse(errors.get(0).contains("last tuesday"), "the error must not echo the value");
    }

    @Test
    void findErrorsShouldCheckTemplateFieldWhenValueIsAbsent() throws Exception {
        // Resolver sends value != null ? value : template, so a static template is sent as is
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("""
                      inputParameters:
                        If-Match:
                          in: header
                          template: "v1"
                """, "", ""));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("If-Match"), errors.get(0));
    }

    @Test
    void findErrorsShouldSkipValueWhenItHoldsATemplate() throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("""
                      inputParameters:
                        If-Match:
                          in: header
                          value: "{{etag}}"
                """, "", ""));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "content-length", "Date", "Connection", "Accept-Encoding",
            "Proxy-Authorization", "Set-Cookie"})
    void findErrorsShouldRefuseAHeaderTheClientCanNeverSend(String name) throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("""
                      inputParameters:
                        %s:
                          in: header
                          value: "{{whatever}}"
                """.formatted(name), "", ""));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains(name) && errors.get(0).contains("never"),
                errors.get(0));
    }

    @Test
    void findErrorsShouldRefuseAWireNameTheClientCanNeverSend() throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("", "", """
                              inputParameters:
                                target:
                                  in: header
                                  wireName: Host
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("up.list") && errors.get(0).contains("Host"),
                errors.get(0));
    }

    @Test
    void findErrorsShouldSkipCustomHeaderWhoseValueIsNeverParsed() throws Exception {
        List<String> errors = ConsumedHeaderValidator.findErrors(spec("""
                      inputParameters:
                        X-Mode:
                          in: header
                          value: "v1"
                """, "", ""));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void capabilityShouldRefuseStaticHeaderValueThatCannotBeParsed() throws Exception {
        IkanosSpec spec = spec("""
                      inputParameters:
                        If-Match:
                          in: header
                          value: "v1"
                """, "", "");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Capability(spec),
                "#1001: a static header value that can never be sent must fail at load time");
        assertTrue(error.getMessage().contains("If-Match"), error.getMessage());
        assertFalse(error.getMessage().contains("v1"), error.getMessage());
    }

    @Test
    void capabilityShouldLoadWhenHeaderValueIsOnlyKnownPerCall() throws Exception {
        assertNotNull(new Capability(spec("""
                      inputParameters:
                        If-Match:
                          in: header
                          value: "{{etag}}"
                """, "", "")));
    }

    /**
     * A capability whose consumed adapter {@code up} carries the given adapter-, resource- and
     * operation-level YAML fragments. Each fragment is indented, relative to its closing
     * delimiter, at the level it takes in the capability (6, 10 and 14 spaces).
     */
    private static IkanosSpec spec(String adapter, String resource, String operation)
            throws Exception {
        return IkanosMappers.lenientYaml().readValue("""
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "localhost"
                      port: 0
                      namespace: "test"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            get:
                              method: "GET"
                              call: up.list
                  consumes:
                    - type: "http"
                      namespace: "up"
                      baseUri: "http://localhost:9/v1"
                %s
                      resources:
                        items:
                          path: "/items"
                %s
                          operations:
                            list:
                              method: GET
                %s
                """.formatted(IKANOS, adapter.stripTrailing(), resource.stripTrailing(),
                operation.stripTrailing()), IkanosSpec.class);
    }
}
