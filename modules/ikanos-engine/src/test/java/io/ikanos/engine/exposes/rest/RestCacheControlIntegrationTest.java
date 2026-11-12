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
package io.ikanos.engine.exposes.rest;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import io.ikanos.Capability;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.exposes.rest.RestServerSpec;
import io.ikanos.spec.util.IkanosMappers;
import io.ikanos.spec.util.VersionHelper;

/**
 * {@code Cache-Control} on REST answers (#1021), through the adapter's resource restlet.
 */
class RestCacheControlIntegrationTest {

    private static final String IKANOS = VersionHelper.getSchemaVersion();

    private static Capability capability;
    private static RestServerSpec spec;

    @BeforeAll
    static void load() throws Exception {
        String yaml = """
                ikanos: "%s"
                capability:
                  exposes:
                  - type: rest
                    port: 9198
                    namespace: catalog
                    ttlMs: 300000
                    cacheScope: auto
                    resources:
                      products:
                        path: "/products"
                        operations:
                          list-products:
                            method: GET
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                          create-product:
                            method: POST
                            outputParameters:
                              status:
                                type: string
                                value: "created"
                      prices:
                        path: "/prices"
                        operations:
                          list-prices:
                            method: GET
                            ttlMs: 0
                            outputParameters:
                              status:
                                type: string
                                value: "ok"
                """.formatted(IKANOS);
        IkanosSpec ikanos = IkanosMappers.lenientYaml().readValue(yaml, IkanosSpec.class);
        capability = new Capability(ikanos);
        spec = ((RestServerAdapter) capability.getServerAdapters().get(0)).getRestServerSpec();
    }

    @Test
    void getShouldSendPublicMaxAgeFromTheAdapterTtl() {
        Response response = handle("products", Method.GET);

        assertThat(response.getStatus().isSuccess()).isTrue();
        assertThat(RestCacheControlTest.cacheControl(response)).isEqualTo("public, max-age=300");
    }

    @Test
    void postShouldSendNoCacheControl() {
        Response response = handle("products", Method.POST);

        assertThat(response.getStatus().isSuccess()).isTrue();
        assertThat(response.getCacheDirectives()).isEmpty();
    }

    @Test
    void getShouldSendNoStoreWhenTheOperationTtlIsZero() {
        assertThat(RestCacheControlTest.cacheControl(handle("prices", Method.GET)))
                .isEqualTo("no-store");
    }

    static Response handle(String resource, Method method) {
        ResourceRestlet restlet =
                new ResourceRestlet(capability, spec, spec.getResources().get(resource));
        Request request = new Request(method,
                new Reference("http://localhost/" + resource));
        Response response = new Response(request);
        restlet.handle(request, response);
        return response;
    }
}
