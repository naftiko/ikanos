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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * An IPv4 or IPv6 network in CIDR notation, e.g. {@code 10.0.0.0/8} or {@code fd00::/8}.
 *
 * <p>Parsing is strict on purpose, since a lenient parser is how IP allow-lists get bypassed: an
 * IPv4 address must be four decimal octets without leading zeros (so {@code 010.0.0.1} and
 * {@code 0x0a.0.0.1} are rejected), and only literal addresses are accepted, so no DNS lookup ever
 * happens. An IPv4-mapped IPv6 peer ({@code ::ffff:10.0.0.1}) matches the IPv4 range it maps
 * to.</p>
 */
public final class CidrRange {

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$");

    private final String text;
    private final byte[] network;
    private final int prefixLength;

    private CidrRange(String text, byte[] network, int prefixLength) {
        this.text = text;
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses a network in CIDR notation.
     *
     * @throws IllegalArgumentException when the value is not a valid IPv4 or IPv6 CIDR.
     */
    public static CidrRange parse(String cidr) {
        if (cidr == null) {
            throw new IllegalArgumentException("CIDR must not be null");
        }
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid CIDR '" + cidr + "': expected <address>/<prefix-length>");
        }
        String prefix = cidr.substring(slash + 1);
        if (!prefix.matches("\\d{1,3}")) {
            throw new IllegalArgumentException("Invalid CIDR '" + cidr + "': bad prefix length");
        }
        byte[] address = parseLiteral(cidr.substring(0, slash));
        if (address == null) {
            throw new IllegalArgumentException(
                    "Invalid CIDR '" + cidr + "': not an IPv4 or IPv6 address literal");
        }
        int prefixLength = Integer.parseInt(prefix);
        if (prefixLength > address.length * 8) {
            throw new IllegalArgumentException("Invalid CIDR '" + cidr + "': prefix length "
                    + prefixLength + " exceeds " + (address.length * 8));
        }
        return new CidrRange(cidr, address, prefixLength);
    }

    /**
     * @return {@code true} when {@code address} is a literal IP address inside this network. A
     *         {@code null}, a hostname or a malformed value never matches.
     */
    public boolean contains(String address) {
        if (address == null) {
            return false;
        }
        byte[] candidate = parseLiteral(address);
        if (candidate == null || candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /**
     * Parses an IP literal without DNS. Returns 4 bytes for IPv4 (including IPv4-mapped IPv6),
     * 16 bytes for IPv6, or {@code null} when the value is not a literal.
     */
    static byte[] parseLiteral(String value) {
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (IPV4.matcher(candidate).matches()) {
            return toBytes(candidate);
        }
        if (IPV6.matcher(candidate).matches()) {
            // Contains ':' so InetAddress treats it as a literal and never resolves it.
            return toBytes(candidate);
        }
        return null;
    }

    private static byte[] toBytes(String literal) {
        try {
            // InetAddress folds IPv4-mapped IPv6 (::ffff:a.b.c.d) into a 4-byte Inet4Address.
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return text;
    }
}
