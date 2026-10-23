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
package io.ikanos.engine.consumes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Integration test: a capability whose consumed template cannot be parsed fails when it is
 * loaded, instead of on the first tool call (#849).
 */
public class ConsumedTemplateLoadIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    @Test
    void newCapabilityShouldFailWhenConsumedBodyTemplateCannotBeParsed() throws Exception {
        IkanosSpec spec = spec("'{\"a\":1{{#x}},\"x\":\"{{x}}\"}'");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Capability(spec));

        assertTrue(e.getMessage().contains("svc.create"), e.getMessage());
    }

    @Test
    void newCapabilityShouldLoadWhenConsumedTemplatesAreValid() throws Exception {
        IkanosSpec spec = spec("'{\"a\":1{{#x}},\"x\":\"{{x}}\"{{/x}}}'");

        assertDoesNotThrow(() -> new Capability(spec));
    }

    private static IkanosSpec spec(String bodyData) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: "http"
                      namespace: "svc"
                      baseUri: "https://api.example.com"
                      resources:
                        items:
                          path: "/items"
                          operations:
                            create:
                              method: "POST"
                              body:
                                type: "json"
                                data: %s
                  exposes:
                    - type: "mcp"
                      port: 0
                      namespace: "tools"
                      tools:
                        create:
                          description: "Create an item"
                          call: "svc.create"
                """.formatted(IKANOS, bodyData);
        return IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
    }
}
