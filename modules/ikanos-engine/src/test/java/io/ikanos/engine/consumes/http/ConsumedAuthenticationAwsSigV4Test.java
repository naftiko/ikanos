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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.restlet.engine.Engine;
import org.restlet.engine.security.AuthenticatorHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.restlet.Request;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.Method;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.consumes.http.AwsSigV4AuthenticationSpec;
import io.ikanos.spec.consumes.http.HttpClientSpec;

/**
 * How {@link ConsumedAuthentication} turns an {@code awsSigV4} authentication into the challenge
 * response {@link AwsSigV4Helper} signs with (#954).
 */
class ConsumedAuthenticationAwsSigV4Test {

    private static final String TARGET = "http://minio.example/documents/a.pdf";

    private static AwsSigV4AuthenticationSpec spec(String region) {
        AwsSigV4AuthenticationSpec spec = new AwsSigV4AuthenticationSpec();
        spec.setAccessKeyId("{{secrets.S3_ACCESS_KEY_ID}}");
        spec.setSecretAccessKey("{{secrets.S3_SECRET_ACCESS_KEY}}".toCharArray());
        spec.setRegion(region);
        return spec;
    }

    private static IkanosSpec capability(AwsSigV4AuthenticationSpec authentication) {
        IkanosSpec spec = new IkanosSpec();
        spec.getConsumes().add(new HttpClientSpec("store", "http://minio.example/documents",
                authentication));
        return spec;
    }

    private static final Map<String, Object> BOUND = Map.of("secrets",
            Map.of("S3_ACCESS_KEY_ID", "AKID", "S3_SECRET_ACCESS_KEY", "s3cr3t",
                    "S3_SESSION_TOKEN", "tok"));

    @Test
    void rendersTheCredentialsAndParametersFromTheBindings() {
        AwsSigV4AuthenticationSpec spec = spec("us-east-1");
        spec.setService("s3");
        spec.setSessionToken("{{secrets.S3_SESSION_TOKEN}}");
        spec.setUnsignedPayload(true);
        Request request = new Request(Method.GET, TARGET);

        ConsumedAuthentication.apply(spec, null, request, TARGET, BOUND);

        ChallengeResponse challenge = request.getChallengeResponse();
        assertSame(AwsSigV4Helper.SCHEME, challenge.getScheme());
        assertEquals("AKID", challenge.getIdentifier());
        assertArrayEquals("s3cr3t".toCharArray(), challenge.getSecret());
        assertEquals("us-east-1",
                challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_REGION));
        assertEquals("s3", challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_SERVICE));
        assertEquals("tok",
                challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_SESSION_TOKEN));
        assertEquals("true",
                challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_UNSIGNED_PAYLOAD));
        assertTrue(org.restlet.engine.Engine.getInstance()
                .findHelper(AwsSigV4Helper.SCHEME, true, false) instanceof AwsSigV4Helper,
                "The helper should be registered before the request is sent");
    }

    @Test
    void omitsTheOptionalParametersWhenNotDeclared() {
        Request request = new Request(Method.GET, TARGET);

        ConsumedAuthentication.apply(spec("eu-west-3"), null, request, TARGET, BOUND);

        ChallengeResponse challenge = request.getChallengeResponse();
        assertNull(challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_SERVICE));
        assertNull(challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_SESSION_TOKEN));
        assertNull(challenge.getParameters().getFirstValue(AwsSigV4Helper.PARAM_UNSIGNED_PAYLOAD));
    }

    @Test
    void doesNotSendAnUnboundSecret() {
        Request request = new Request(Method.GET, TARGET);
        Map<String, Object> missingSecret =
                Map.of("secrets", Map.of("S3_ACCESS_KEY_ID", "AKID"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(spec("us-east-1"), null, request, TARGET,
                        missingSecret));

        assertTrue(e.getMessage().contains("awsSigV4 secret access key"), e.getMessage());
        assertTrue(e.getMessage().contains("S3_SECRET_ACCESS_KEY"), e.getMessage());
        assertFalse(e.getMessage().contains("AKID"), "The error must not show a credential");
        assertNull(request.getChallengeResponse());
    }

    @Test
    void doesNotSendWithoutARegion() {
        Request request = new Request(Method.GET, TARGET);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConsumedAuthentication.apply(spec(null), null, request, TARGET, BOUND));

        assertTrue(e.getMessage().contains("region"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"), "The error must not show a credential");
        assertNull(request.getChallengeResponse());
    }

    @Test
    void listsItsTemplatesForTheLoadTimeCheck() {
        AwsSigV4AuthenticationSpec spec = spec("{{region}}");
        spec.setSessionToken("{{secrets.S3_SESSION_TOKEN}}");

        assertEquals(List.of("store access key ID", "store secret access key", "store region",
                "store session token"),
                List.copyOf(ConsumedAuthentication.credentials(spec, "store").keySet()));
        assertFalse(ConsumedAuthentication.canRender(spec, BOUND),
                "An unbound region should keep the credential from being rendered");
    }

    /** #957 review: awsSigV4 is a type the engine sends, so a capability with it loads. */
    @Test
    void isAcceptedWhenTheCapabilityLoads() {
        assertEquals(List.of(),
                ConsumedAuthentication.findUnsupported(capability(spec("us-east-1"))));
    }

    /**
     * #957 review: like an incomplete apikey (#1004), a missing or blank accessKeyId,
     * secretAccessKey or region is refused when the capability loads, not on every call, since
     * the engine doesn't run the schema.
     */
    @ParameterizedTest(name = "accessKeyId \"{0}\", secretAccessKey \"{1}\", region \"{2}\"")
    @CsvSource(value = {"| {{S}} | us-east-1 | no accessKeyId",
            "{{A}} | | us-east-1 | no secretAccessKey",
            "{{A}} | {{S}} | | no region",
            "'  ' | {{S}} | us-east-1 | an empty accessKeyId",
            "{{A}} | '' | us-east-1 | an empty secretAccessKey",
            "{{A}} | {{S}} | ' ' | an empty region",
            "| | | no accessKeyId, no secretAccessKey and no region"}, delimiter = '|')
    void isRefusedWhenTheCapabilityLoadsWithoutAKeyASecretOrARegion(String accessKeyId,
            String secretAccessKey, String region, String lacks) {
        AwsSigV4AuthenticationSpec spec = new AwsSigV4AuthenticationSpec();
        spec.setAccessKeyId(accessKeyId);
        spec.setSecretAccessKey(secretAccessKey == null ? null : secretAccessKey.toCharArray());
        spec.setRegion(region);

        List<String> errors = ConsumedAuthentication.findUnsupported(capability(spec));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("consumes 'store': an awsSigV4 credential"),
                errors.get(0));
        assertTrue(errors.get(0).endsWith("this one has " + lacks), errors.get(0));
    }

    /**
     * Copilot on #957: two first calls on different threads must not both register the helper.
     * Each round removes it, then releases the threads together.
     */
    @Test
    void registersTheHelperOnceWhenCalledConcurrently() throws Exception {
        List<AuthenticatorHelper> registered = Engine.getInstance().getRegisteredAuthenticators();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                registered.removeIf(AwsSigV4Helper.class::isInstance);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        AwsSigV4Helper.ensureRegistered();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> call : calls) {
                    call.get(10, TimeUnit.SECONDS);
                }
                assertEquals(1, registered.stream().filter(AwsSigV4Helper.class::isInstance)
                        .count(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
            registered.removeIf(AwsSigV4Helper.class::isInstance);
            AwsSigV4Helper.ensureRegistered();
        }
    }
}
