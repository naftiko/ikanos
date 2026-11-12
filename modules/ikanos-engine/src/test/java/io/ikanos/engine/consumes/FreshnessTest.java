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

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FreshnessTest {

    private static final String DATE = "Thu, 12 Nov 2026 10:00:00 GMT";
    private static final long NOW = 1_794_477_600_000L; // 12 Nov 2026 10:00:00 GMT

    @Test
    void parseShouldReadMaxAgeInMilliseconds() {
        assertThat(Freshness.parse("public, max-age=300", null, null, NOW).ttlMs())
                .isEqualTo(300_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-store", "no-cache", "max-age=300, no-cache", "No-Store"})
    void parseShouldGiveZeroWhenTheUpstreamForbidsReuse(String cacheControl) {
        assertThat(Freshness.parse(cacheControl, null, null, NOW).ttlMs()).isZero();
    }

    @Test
    void parseShouldPreferMaxAgeOverExpires() {
        Freshness freshness =
                Freshness.parse("max-age=60", "Thu, 12 Nov 2026 11:00:00 GMT", DATE, NOW);

        assertThat(freshness.ttlMs()).isEqualTo(60_000L);
    }

    @Test
    void parseShouldCountExpiresFromTheDateHeader() {
        Freshness freshness =
                Freshness.parse(null, "Thu, 12 Nov 2026 10:10:00 GMT", DATE, 0L);

        assertThat(freshness.ttlMs()).isEqualTo(600_000L);
    }

    @Test
    void parseShouldCountExpiresFromNowWhenDateIsAbsent() {
        assertThat(Freshness.parse(null, "Thu, 12 Nov 2026 10:01:00 GMT", null, NOW).ttlMs())
                .isEqualTo(60_000L);
    }

    @Test
    void parseShouldTreatAnInvalidOrPastExpiresAsExpired() {
        assertThat(Freshness.parse(null, "0", null, NOW).ttlMs()).isZero();
        assertThat(Freshness.parse(null, "Thu, 12 Nov 2026 09:00:00 GMT", DATE, NOW).ttlMs())
                .isZero();
    }

    @Test
    void parseShouldReturnNoTtlWhenTheUpstreamGivesNoFreshness() {
        assertThat(Freshness.parse("public", null, null, NOW).ttlMs()).isNull();
        assertThat(Freshness.parse(null, null, null, NOW)).isEqualTo(Freshness.UNKNOWN);
    }

    @Test
    void parseShouldIgnoreAnInvalidMaxAge() {
        assertThat(Freshness.parse("max-age=soon", null, null, NOW).ttlMs()).isNull();
    }

    @Test
    void parseShouldCapAHugeMaxAgeToTheIntRange() {
        assertThat(Freshness.parse("max-age=99999999999", null, null, NOW).ttlMs())
                .isEqualTo((long) Integer.MAX_VALUE);
    }

    @Test
    void ofShouldCountParsedExpiresFromTheParsedDate() {
        assertThat(Freshness.of(null, NOW + 120_000L, NOW, 0L).ttlMs()).isEqualTo(120_000L);
    }

    @Test
    void ofShouldReadMaxAgeFromDirectivesJoinedByAClientLibrary() {
        assertThat(Freshness.of("private, max-age=60", null, null, NOW))
                .isEqualTo(new Freshness(60_000L, true));
    }

    @Test
    void parseShouldReportAPrivateAnswer() {
        assertThat(Freshness.parse("private, max-age=60", null, null, NOW).privateOnly()).isTrue();
        assertThat(Freshness.parse("public, max-age=60", null, null, NOW).privateOnly()).isFalse();
    }
}
