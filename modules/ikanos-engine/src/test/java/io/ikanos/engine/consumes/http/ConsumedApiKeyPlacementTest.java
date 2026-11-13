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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;

/**
 * #840: an apikey credential without placement is sent as a header, as the schema says and as
 * the exposed side already reads it, instead of failing the call.
 */
class ConsumedApiKeyPlacementTest {

    private static final String TARGET = "https://api.example.com/items";

    @Test
    void applyShouldSendTheKeyAsAHeaderWhenPlacementIsOmitted() {
        Request request = new Request(Method.GET, TARGET);

        ConsumedAuthentication.apply(new ApiKeyAuthenticationSpec("X-Api-Key", null, "k1"), null,
                request, TARGET, Map.of());

        assertEquals("k1", request.getHeaders().getFirstValue("X-Api-Key"));
        assertEquals(TARGET, request.getResourceRef().toString());
    }

    @Test
    void applyShouldSendAnAuthorizationKeyRawWhenPlacementIsOmitted() {
        Request request = new Request(Method.GET, TARGET);

        ConsumedAuthentication.apply(
                new ApiKeyAuthenticationSpec("Authorization", null, "Token k1"), null, request,
                TARGET, Map.of());

        assertEquals("Token k1", request.getChallengeResponse().getIdentifier());
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query"})
    void applyShouldClearAnEarlierAuthorizationWhenTheKeyIsSentElsewhere(String placement) {
        Request request = new Request(Method.GET, TARGET);
        StandardRequestHeaders.set(request, "Authorization", "Bearer caller-token");

        ConsumedAuthentication.apply(new ApiKeyAuthenticationSpec("X-Api-Key", placement, "k1"),
                null, request, TARGET, Map.of());

        assertNull(request.getChallengeResponse(),
                "#1001: the declared authentication owns Authorization, so no other credential "
                        + "goes out beside the API key");
    }

    @Test
    void applyShouldPutTheKeyInTheQueryWhateverTheCaseOfThePlacement() {
        Request request = new Request(Method.GET, TARGET);

        ConsumedAuthentication.apply(new ApiKeyAuthenticationSpec("api_key", "Query", "k1"), null,
                request, TARGET, Map.of());

        assertEquals(TARGET + "?api_key=k1", request.getResourceRef().toString());
        assertNull(request.getHeaders().getFirstValue("api_key"));
    }

    /** #841: the key and its name are URL-encoded, so the API reads the value it was given. */
    @Test
    void applyShouldUrlEncodeAQueryKeyAndItsName() {
        Request request = new Request(Method.GET, TARGET);

        ConsumedAuthentication.apply(new ApiKeyAuthenticationSpec("api key", "query", "{{KEY}}"),
                null, request, TARGET, Map.of("KEY", "a+b&c=d #é"));

        assertEquals(TARGET + "?api+key=a%2Bb%26c%3Dd+%23%C3%A9",
                request.getResourceRef().toString());
        assertEquals("a+b&c=d #é",
                request.getResourceRef().getQueryAsForm().getFirstValue("api key"));
    }

    @Test
    void applyShouldAppendAnEncodedQueryKeyAfterAnExistingQuery() {
        String target = TARGET + "?page=2";
        Request request = new Request(Method.GET, target);

        ConsumedAuthentication.apply(new ApiKeyAuthenticationSpec("api_key", "query", "a&b"),
                null, request, target, Map.of());

        assertEquals(target + "&api_key=a%26b", request.getResourceRef().toString());
    }

    /**
     * An apikey without a key or a value (the schema only requires {@code type}) can't be sent:
     * a clear error, not a NullPointerException from the encoding, nor the text "null".
     */
    @ParameterizedTest(name = "placement: {0}")
    @ValueSource(strings = {"query", "header"})
    void applyShouldRefuseAnApiKeyWithoutAValue(String placement) {
        Request request = new Request(Method.GET, TARGET);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(
                        new ApiKeyAuthenticationSpec("api_key", placement, null), null, request,
                        TARGET, Map.of()));

        assertTrue(e.getMessage().contains("key and a value"), e.getMessage());
        assertEquals(TARGET, request.getResourceRef().toString());
    }

    @Test
    void applyShouldRefuseAnApiKeyWithoutAKey() {
        Request request = new Request(Method.GET, TARGET);

        assertThrows(IllegalArgumentException.class, () -> ConsumedAuthentication.apply(
                new ApiKeyAuthenticationSpec(null, "query", "k1"), null, request, TARGET,
                Map.of()));
    }
}
