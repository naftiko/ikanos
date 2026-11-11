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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * Unit tests for {@link HttpClientAdapter#prepare} with the {@code queryParameters} list of a
 * consumed resource or operation (#987).
 */
public class HttpClientAdapterQueryParametersTest {

    @Test
    public void prepareShouldSendListedQueryParametersFromTheCallParameters() throws Exception {
        Request request = prepare("list-issues", Map.of("state", "open", "page", 2));

        assertEquals("https://probe.example/v1/issues?state=open&page=2",
                request.getResourceRef().toString(),
                "#987: each listed name should send the call parameter of the same name");
    }

    @Test
    public void prepareShouldLeaveOutListedQueryParametersThatAreAbsent() throws Exception {
        Request request = prepare("list-issues", Map.of("state", "open"));

        assertEquals("https://probe.example/v1/issues?state=open",
                request.getResourceRef().toString(),
                "#987: a listed name without a call parameter should be left out");
    }

    @Test
    public void prepareShouldSendTheSameRequestAsTheLongForm() throws Exception {
        Map<String, Object> arguments = Map.of("state", "open", "page", 2, "per_page", 50);

        assertEquals(prepare("list-issues-long", arguments).getResourceRef().toString(),
                prepare("list-issues", arguments).getResourceRef().toString(),
                "#987: the list should be exactly equivalent to in: query entries");
    }

    @Test
    public void prepareShouldSendResourceLevelListedNamesForEveryOperation() throws Exception {
        Request request = prepare("count-issues", Map.of("per_page", 10));

        assertEquals("https://probe.example/v1/issues?per_page=10",
                request.getResourceRef().toString());
    }

    @Test
    public void prepareShouldLetAnOperationDeclarationReplaceAResourceLevelListedName()
            throws Exception {
        Request request = prepare("first-page", Map.of("per_page", 10));

        assertEquals("https://probe.example/v1/issues?per_page=100",
                request.getResourceRef().toString(),
                "#987/#909: the operation's per_page replaces the resource's, sent once");
    }

    private Request prepare(String operation, Map<String, Object> arguments) throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  consumes:
                    - type: http
                      namespace: api
                      baseUri: "https://probe.example/v1"
                      resources:
                        issues:
                          path: "/issues"
                          queryParameters: [per_page]
                          operations:
                            list-issues:
                              method: GET
                              queryParameters: [state, page]
                            list-issues-long:
                              method: GET
                              inputParameters:
                                state:
                                  in: query
                                page:
                                  in: query
                            count-issues:
                              method: GET
                            first-page:
                              method: GET
                              inputParameters:
                                per_page:
                                  in: query
                                  value: "100"
                """.formatted(VersionHelper.getSchemaVersion());
        IkanosSpec spec = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        Capability capability = mock(Capability.class);
        when(capability.getBindings()).thenReturn(Map.of());
        HttpClientAdapter adapter = new HttpClientAdapter(capability,
                (HttpClientSpec) spec.getCapability().getConsumes().get(0));
        return adapter.prepare(operation, new HashMap<>(arguments)).getRequest();
    }
}
