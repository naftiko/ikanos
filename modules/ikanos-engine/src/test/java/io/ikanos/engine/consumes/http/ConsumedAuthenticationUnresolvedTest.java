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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restlet.Request;
import org.restlet.data.Method;
import io.ikanos.spec.consumes.http.AuthenticationSpec;
import io.ikanos.spec.consumes.http.ApiKeyAuthenticationSpec;
import io.ikanos.spec.consumes.http.BasicAuthenticationSpec;
import io.ikanos.spec.consumes.http.BearerAuthenticationSpec;
import io.ikanos.spec.consumes.http.DigestAuthenticationSpec;

/**
 * #843: a consumed credential whose variable is unbound (a typo such as {@code {{TOKN}}}) or
 * bound to an empty value is never sent. Before, it rendered as an empty string, so the upstream
 * got {@code Authorization: Bearer } with no warning.
 */
class ConsumedAuthenticationUnresolvedTest {

    private static final String TARGET = "https://api.example.com/items";

    @Test
    void bearerTokenWithMisspelledVariableShouldNotBeSent() {
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(bearer("{{TOKN}}"), null, request, TARGET,
                        Map.of("TOKEN", "s3cr3t", "id", "42")));

        assertTrue(e.getMessage().contains("TOKN"), e.getMessage());
        assertTrue(e.getMessage().contains("bearer token"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"), "The error must not show a secret");
        assertNull(request.getChallengeResponse());
    }

    @Test
    void bearerTokenWithEmptyBindingShouldNotBeSent() {
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(bearer("{{TOKEN}}"), null, request, TARGET,
                        Map.of("TOKEN", "", "id", "42")));

        assertTrue(e.getMessage().contains("TOKEN"), e.getMessage());
        assertNull(request.getChallengeResponse());
    }

    @Test
    void bearerTokenWithNoParametersAtAllShouldNotSendThePlaceholder() {
        Request request = request();

        assertThrows(IllegalArgumentException.class, () -> ConsumedAuthentication
                .apply(bearer("{{TOKEN}}"), null, request, TARGET, Map.of()));

        assertNull(request.getChallengeResponse());
    }

    @Test
    void bearerTokenWithPrefixAndMisspelledVariableShouldNotBeSent() {
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(bearer("sk-{{TOKN}}"), null, request, TARGET,
                        Map.of("TOKEN", "s3cr3t")));

        assertTrue(e.getMessage().contains("TOKN"), e.getMessage());
        assertNull(request.getChallengeResponse());
    }

    @Test
    void bearerTokenWithBoundVariableShouldBeSent() {
        Request request = request();

        ConsumedAuthentication.apply(bearer("{{TOKEN}}"), null, request, TARGET,
                Map.of("TOKEN", "s3cr3t"));

        assertEquals("s3cr3t", request.getChallengeResponse().getRawValue());
    }

    @Test
    void staticBearerTokenShouldBeSentWithoutParameters() {
        Request request = request();

        ConsumedAuthentication.apply(bearer("static-token"), null, request, TARGET, Map.of());

        assertEquals("static-token", request.getChallengeResponse().getRawValue());
    }

    @ParameterizedTest(name = "placement: {0}")
    @ValueSource(strings = {"header", "query"})
    void apiKeyValueWithMisspelledVariableShouldNotBeSent(String placement) {
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(apiKey("X-Api-Key", "{{API_KY}}", placement),
                        null, request, TARGET, Map.of("API_KEY", "s3cr3t")));

        assertTrue(e.getMessage().contains("API_KY"), e.getMessage());
        assertTrue(e.getMessage().contains("apikey value"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"), "The error must not show a secret");
        assertNull(request.getHeaders().getFirstValue("X-Api-Key"));
        assertEquals(TARGET, request.getResourceRef().toString());
    }

    @Test
    void apiKeyKeyWithEmptyBindingShouldNotBeSent() {
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(apiKey("{{KEY_NAME}}", "s3cr3t", "header"),
                        null, request, TARGET, Map.of("KEY_NAME", " ")));

        assertTrue(e.getMessage().contains("KEY_NAME"), e.getMessage());
        assertTrue(e.getMessage().contains("apikey key"), e.getMessage());
    }

    @Test
    void apiKeyWithBoundVariableShouldBeSent() {
        Request request = request();

        ConsumedAuthentication.apply(apiKey("X-Api-Key", "{{API_KEY}}", "header"), null, request,
                TARGET, Map.of("API_KEY", "s3cr3t"));

        assertEquals("s3cr3t", request.getHeaders().getFirstValue("X-Api-Key"));
    }

    @Test
    void basicPasswordWithMisspelledVariableShouldNotBeSent() {
        BasicAuthenticationSpec basic = new BasicAuthenticationSpec();
        basic.setType("basic");
        basic.setUsername("{{USER}}");
        basic.setPassword("{{PASWORD}}".toCharArray());
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(basic, null, request, TARGET,
                        Map.of("USER", "admin", "PASSWORD", "s3cr3t")));

        assertTrue(e.getMessage().contains("PASWORD"), e.getMessage());
        assertTrue(e.getMessage().contains("basic password"), e.getMessage());
        assertNull(request.getChallengeResponse());
    }

    @Test
    void digestUsernameWithEmptyBindingShouldNotBeSent() {
        DigestAuthenticationSpec digest = new DigestAuthenticationSpec();
        digest.setType("digest");
        digest.setUsername("{{USER}}");
        Request request = request();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(digest, null, request, TARGET,
                        Map.of("USER", "")));

        assertTrue(e.getMessage().contains("digest username"), e.getMessage());
        assertNull(request.getChallengeResponse());
    }

    private static Request request() {
        return new Request(Method.GET, TARGET);
    }

    private static AuthenticationSpec bearer(String token) {
        BearerAuthenticationSpec bearer = new BearerAuthenticationSpec();
        bearer.setType("bearer");
        bearer.setToken(token);
        return bearer;
    }

    private static AuthenticationSpec apiKey(String key, String value, String placement) {
        ApiKeyAuthenticationSpec apiKey = new ApiKeyAuthenticationSpec();
        apiKey.setType("apikey");
        apiKey.setKey(key);
        apiKey.setValue(value);
        apiKey.setPlacement(placement);
        return apiKey;
    }
}
