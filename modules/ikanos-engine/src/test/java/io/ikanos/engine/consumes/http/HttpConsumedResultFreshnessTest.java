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

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Status;

/**
 * {@link HttpConsumedResult#freshness()} measures freshness when the headers arrived, then ages
 * it by the time spent since, such as downloading the body (#1019).
 */
class HttpConsumedResultFreshnessTest {

    private static HttpConsumedResult receivedAgo(long millis, int maxAge, Integer age) {
        Response response = new Response(new Request());
        response.setStatus(Status.SUCCESS_OK);
        // freshness() reads the raw headers, as the HTTP client connector fills them
        response.getHeaders().add("Cache-Control", "max-age=" + maxAge);
        if (age != null) {
            response.getHeaders().add("Age", String.valueOf(age));
        }
        long now = System.currentTimeMillis();
        return new HttpConsumedResult(null, response, now - millis, now - millis);
    }

    @Test
    void freshnessShouldCorrectTheAgeByTheTimeTheAnswerTookToArrive() {
        Response response = new Response(new Request());
        response.setStatus(Status.SUCCESS_OK);
        response.getHeaders().add("Cache-Control", "max-age=60");
        response.getHeaders().add("Age", "30");
        long now = System.currentTimeMillis();

        // the request was sent 10 s before the client returned the answer
        long ttl = new HttpConsumedResult(null, response, now - 10_000L, now).freshness().ttlMs();

        assertThat(ttl).isBetween(19_000L, 20_000L);
    }

    @Test
    void freshnessShouldDeductTheTimeSinceTheHeadersArrived() {
        long ttl = receivedAgo(10_000L, 60, null).freshness().ttlMs();

        assertThat(ttl).isBetween(49_000L, 50_000L);
    }

    @Test
    void freshnessShouldDeductTheTimeSinceTheHeadersArrivedOnTopOfTheAge() {
        long ttl = receivedAgo(10_000L, 60, 30).freshness().ttlMs();

        assertThat(ttl).isBetween(19_000L, 20_000L);
    }

    @Test
    void freshnessShouldBeZeroWhenTheBodyTookLongerThanTheTimeLeft() {
        assertThat(receivedAgo(10_000L, 300, 295).freshness().ttlMs()).isZero();
    }
}
