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

    static Freshness parse(String cacheControl, String expires, String date, String age) {
        return Freshness.parse(cacheControl, expires, date, age, NOW);
    }

    @Test
    void parseShouldReadMaxAgeInMilliseconds() {
        assertThat(parse("public, max-age=300", null, null, null).ttlMs()).isEqualTo(300_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-store", "no-cache", "max-age=300, no-cache", "No-Store",
            "no-cache=\"Set-Cookie\""})
    void parseShouldGiveZeroWhenTheUpstreamForbidsReuse(String cacheControl) {
        assertThat(parse(cacheControl, null, null, null).ttlMs()).isZero();
    }

    @Test
    void parseShouldUseSMaxAgeWhenItIsShorterThanMaxAge() {
        assertThat(parse("max-age=600, s-maxage=60", null, null, null).ttlMs())
                .isEqualTo(60_000L);
    }

    @Test
    void parseShouldKeepMaxAgeWhenSMaxAgeIsLonger() {
        assertThat(parse("max-age=60, s-maxage=600", null, null, null).ttlMs())
                .isEqualTo(60_000L);
    }

    @Test
    void parseShouldUseSMaxAgeAlone() {
        assertThat(parse("s-maxage=120", null, null, null).ttlMs()).isEqualTo(120_000L);
    }

    @Test
    void parseShouldSubtractTheAgeOfACachedAnswer() {
        assertThat(parse("max-age=300", null, null, "290").ttlMs()).isEqualTo(10_000L);
    }

    @Test
    void parseShouldNotGoBelowZeroWhenTheAgeExceedsTheLifetime() {
        assertThat(parse("max-age=300", null, null, "900").ttlMs()).isZero();
    }

    @Test
    void parseShouldPreferMaxAgeOverExpires() {
        assertThat(parse("max-age=60", "Thu, 12 Nov 2026 11:00:00 GMT", DATE, null).ttlMs())
                .isEqualTo(60_000L);
    }

    @Test
    void parseShouldCountExpiresFromTheDateHeader() {
        assertThat(Freshness.parse(null, "Thu, 12 Nov 2026 10:10:00 GMT", DATE, null, 0L).ttlMs())
                .isEqualTo(600_000L);
    }

    @Test
    void parseShouldCountExpiresFromNowWhenDateIsAbsent() {
        assertThat(parse(null, "Thu, 12 Nov 2026 10:01:00 GMT", null, null).ttlMs())
                .isEqualTo(60_000L);
    }

    @Test
    void parseShouldSubtractTheAgeFromExpires() {
        assertThat(parse(null, "Thu, 12 Nov 2026 10:10:00 GMT", DATE, "60").ttlMs())
                .isEqualTo(540_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "soon"})
    void parseShouldTreatAnInvalidExpiresAsExpired(String expires) {
        assertThat(parse(null, expires, null, null).ttlMs()).isZero();
    }

    @Test
    void parseShouldTreatAPastExpiresAsExpired() {
        assertThat(parse(null, "Thu, 12 Nov 2026 09:00:00 GMT", DATE, null).ttlMs()).isZero();
    }

    @Test
    void parseShouldReturnNoTtlWhenCacheControlHasNoLifetime() {
        assertThat(parse("public", null, null, null).ttlMs()).isNull();
    }

    @Test
    void parseShouldReturnUnknownWhenTheUpstreamSendsNoCachingHeaders() {
        assertThat(parse(null, null, null, null)).isEqualTo(Freshness.UNKNOWN);
    }

    @Test
    void parseShouldIgnoreAnInvalidMaxAge() {
        assertThat(parse("max-age=soon", null, null, null).ttlMs()).isNull();
    }

    @Test
    void parseShouldCapAHugeMaxAgeToTheIntRange() {
        assertThat(parse("max-age=99999999999", null, null, null).ttlMs())
                .isEqualTo((long) Integer.MAX_VALUE);
    }

    @Test
    void parseShouldKeepTheRemainingFreshnessOfALongLivedAnswer() {
        assertThat(parse("max-age=2592000", null, null, "2500000").ttlMs())
                .isEqualTo(92_000_000L);
    }

    @Test
    void parseShouldSubtractBeforeCappingAHugeLifetime() {
        assertThat(parse("max-age=99999999999", null, null, "99999999998").ttlMs())
                .isEqualTo(1_000L);
    }

    @Test
    void parseShouldTreatAnExpiresPastAnOldDateAsExpiredWithoutAge() {
        // Date 10:00, Expires 10:05, received at 10:10
        assertThat(Freshness.parse(null, "Thu, 12 Nov 2026 10:05:00 GMT", DATE, null,
                NOW + 600_000L).ttlMs()).isZero();
    }

    @Test
    void parseShouldAgeAMaxAgeFromAnOldDateWithoutAge() {
        // Date 10:00, max-age ten minutes, received at 10:05
        assertThat(Freshness.parse("max-age=600", null, DATE, null, NOW + 300_000L).ttlMs())
                .isEqualTo(300_000L);
    }

    @Test
    void parseShouldUseTheTimeSinceDateWhenItExceedsTheAge() {
        assertThat(Freshness.parse("max-age=600", null, DATE, "60", NOW + 300_000L).ttlMs())
                .isEqualTo(300_000L);
    }

    @Test
    void parseShouldUseTheAgeWhenItExceedsTheTimeSinceDate() {
        assertThat(Freshness.parse("max-age=600", null, DATE, "400", NOW + 300_000L).ttlMs())
                .isEqualTo(200_000L);
    }

    @Test
    void parseShouldIgnoreADateAheadOfTheLocalClock() {
        assertThat(Freshness.parse("max-age=60", null, DATE, null, NOW - 30_000L).ttlMs())
                .isEqualTo(60_000L);
    }

    @Test
    void parseShouldReportAPrivateAnswer() {
        assertThat(parse("private, max-age=60", null, null, null).privateOnly()).isTrue();
    }

    @Test
    void parseShouldReportAPublicAnswerAsNotPrivate() {
        assertThat(parse("public, max-age=60", null, null, null).privateOnly()).isFalse();
    }

    @Test
    void parseShouldNotSplitAQuotedValueOnItsCommas() {
        Freshness freshness = parse("private=\"Set-Cookie, X-Token\", max-age=60", null, null,
                null);

        assertThat(freshness).isEqualTo(new Freshness(60_000L, true));
    }

    @Test
    void parseShouldReadSeveralCacheControlHeadersJoinedByCommas() {
        assertThat(parse("public,max-age=30", null, null, null).ttlMs()).isEqualTo(30_000L);
    }
}
