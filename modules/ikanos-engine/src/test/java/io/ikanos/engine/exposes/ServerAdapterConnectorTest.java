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
package io.ikanos.engine.exposes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.VersionHelper;

/**
 * Settings that {@link ServerAdapter#initServer} applies to every server connector.
 */
class ServerAdapterConnectorTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void initServerShouldNeverTakeTheClientAddressFromXForwardedFor() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                    - type: "rest"
                      address: "127.0.0.1"
                      port: 0
                      namespace: "connector-test"
                      resources:
                        - path: "/ping"
                          operations:
                            - method: "GET"
                              name: "ping"
                  consumes: []
                """.formatted(VersionHelper.getSchemaVersion());
        ServerAdapter adapter = (ServerAdapter) new Capability(
                YAML_MAPPER.readValue(yaml, IkanosSpec.class)).getServerAdapters().get(0);

        // With "true", Restlet would report the X-Forwarded-For value as the client address,
        // letting any caller pass the MCP trustedNetworks check (issue #483).
        assertEquals("false", adapter.getServer().getContext().getParameters()
                .getFirstValue("useForwardedForHeader"));
    }
}
