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
package io.ikanos.engine.exposes.mcp.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UploadClaimsTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void claimShouldSucceedOnlyOnce() {
        UploadClaims claims = new UploadClaims(Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(claims.claim("state-1", NOW.plusSeconds(600)));
        assertFalse(claims.claim("state-1", NOW.plusSeconds(600)));
        assertTrue(claims.claim("state-2", NOW.plusSeconds(600)));
    }

    @Test
    void claimShouldMakeRoomByDroppingExpiredClaims() {
        UploadClaims claims = new UploadClaims(Clock.fixed(NOW, ZoneOffset.UTC), 2);
        claims.claim("old-1", NOW.minus(Duration.ofSeconds(1)));
        claims.claim("old-2", NOW.minus(Duration.ofSeconds(1)));

        assertTrue(claims.claim("new", NOW.plusSeconds(600)));
        assertEquals(1, claims.size());
    }

    @Test
    void claimShouldRefuseWhenFullOfUnexpiredClaims() {
        UploadClaims claims = new UploadClaims(Clock.fixed(NOW, ZoneOffset.UTC), 1);
        claims.claim("live", NOW.plusSeconds(600));

        assertThrows(IllegalStateException.class,
                () -> claims.claim("other", NOW.plusSeconds(600)));
    }
}
