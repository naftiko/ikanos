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
package io.ikanos.engine.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrRangeTest {

    @Test
    void containsShouldMatchAddressInsideIpv4Network() {
        assertTrue(CidrRange.parse("10.0.0.0/8").contains("10.0.3.7"));
    }

    @Test
    void containsShouldRejectAddressOutsideIpv4Network() {
        assertFalse(CidrRange.parse("10.0.0.0/8").contains("85.12.4.9"));
    }

    @Test
    void containsShouldHonourPrefixThatIsNotAByteBoundary() {
        CidrRange range = CidrRange.parse("192.168.4.0/22");
        assertTrue(range.contains("192.168.7.255"));
        assertFalse(range.contains("192.168.8.0"));
    }

    @Test
    void containsShouldMatchOnlyTheExactHostForSlash32() {
        CidrRange range = CidrRange.parse("127.0.0.1/32");
        assertTrue(range.contains("127.0.0.1"));
        assertFalse(range.contains("127.0.0.2"));
    }

    @Test
    void containsShouldMatchEverythingForSlash0() {
        assertTrue(CidrRange.parse("0.0.0.0/0").contains("203.0.113.7"));
    }

    @Test
    void containsShouldMatchAddressInsideIpv6Network() {
        CidrRange range = CidrRange.parse("fd00::/8");
        assertTrue(range.contains("fd12:3456::1"));
        assertFalse(range.contains("2001:db8::1"));
    }

    @Test
    void containsShouldMatchBracketedIpv6Loopback() {
        assertTrue(CidrRange.parse("::1/128").contains("[0:0:0:0:0:0:0:1]"));
    }

    @Test
    void containsShouldMatchIpv4MappedIpv6PeerAgainstIpv4Network() {
        assertTrue(CidrRange.parse("10.0.0.0/8").contains("::ffff:10.0.3.7"));
    }

    @Test
    void containsShouldNotMixIpv4AndIpv6Families() {
        assertFalse(CidrRange.parse("::/0").contains("10.0.3.7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "010.0.3.7", "0x0a.0.3.7", "10.0.3",
        "10.0.3.7.1", "", "not an ip"})
    void containsShouldRejectNonLiteralOrMalformedPeers(String peer) {
        assertFalse(CidrRange.parse("0.0.0.0/0").contains(peer));
    }

    @Test
    void containsShouldRejectNullPeer() {
        assertFalse(CidrRange.parse("0.0.0.0/0").contains(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0", "10.0.0.0/33", "fd00::/129", "010.0.0.0/8",
        "0x0a.0.0.0/8", "example.com/8", "10.0.0.0/8/8", "/8", "10.0.0.0/", "10.0.0.0/-1",
        "10.0.0.0/abc", "256.0.0.0/8"})
    void parseShouldRejectInvalidCidr(String cidr) {
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse(cidr));
    }

    @Test
    void parseShouldRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> CidrRange.parse(null));
    }
}
