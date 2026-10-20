/**
 * Spectral custom function: mcp-trust-networks
 *
 * Checks `trustedNetworks` of an MCP adapter in `trust: forwarded` mode (issue #483). Only
 * direct peers in these networks may set the agent identity header, so they should be the
 * gateway's own addresses. Selected by `functionOptions.check`:
 *
 * - `broad`: flags networks wider than an IPv4 /8 or an IPv6 /16 (0.0.0.0/0 and ::/0
 *   included), which trust far more peers than a gateway.
 * - `loopback`: flags networks that overlap loopback (127.0.0.0/8, ::1) when the adapter
 *   listens beyond loopback: a co-located process or sidecar could then set the header.
 *
 * IPv4-mapped IPv6 entries (`::ffff:a.b.c.d/96` to `/128`) are read as the IPv4 network they
 * map, as the engine does. Entries that are not valid CIDRs are left to the schema.
 */
const MIN_IPV4_PREFIX = 8;
const MIN_IPV6_PREFIX = 16;

export default function mcpTrustNetworks(adapter, options, context) {
  if (!adapter || typeof adapter !== "object" || !adapter.trust) {
    return;
  }
  const networks = adapter.trust.trustedNetworks;
  if (adapter.trust.mode !== "forwarded" || !Array.isArray(networks)) {
    return;
  }
  const check = options && options.check;
  if (check === "loopback" && isLoopbackAddress(adapter.address)) {
    return;
  }

  const results = [];
  networks.forEach((entry, index) => {
    const network = parseCidr(entry);
    if (!network) {
      return;
    }
    const path = [...context.path, "trust", "trustedNetworks", index];
    if (check === "broad" && isTooBroad(network)) {
      results.push({
        message: `'${entry}' trusts far more peers than a gateway. List the gateway's own `
          + "addresses, and pair 'forwarded' with 'authentication'.",
        path,
      });
    } else if (check === "loopback" && containsLoopback(network)) {
      results.push({
        message: `'${entry}' trusts loopback peers while the adapter listens on `
          + `'${adapter.address}'. A co-located process could set the agent identity header.`,
        path,
      });
    }
  });
  return results;
}

/**
 * An omitted address defaults to localhost in the engine. Other values count as loopback only
 * when they are a full IPv4 literal in 127.0.0.0/8 or an IPv6 literal equal to ::1 (in any
 * spelling, IPv4-mapped 127.x.x.x included); a hostname such as 127.example.com does not.
 */
function isLoopbackAddress(address) {
  if (address === undefined || address === null) {
    return true;
  }
  const value = String(address).replace(/^\[|\]$/g, "").toLowerCase();
  if (value === "localhost") {
    return true;
  }
  if (!value.includes(":")) {
    const v4 = ipv4Bits(value);
    return v4 !== null && samePrefix(v4, ipv4Bits("127.0.0.0"), 8);
  }
  const v6 = ipv6Bits(value);
  if (v6 === null) {
    return false;
  }
  if (isIpv4Mapped(v6)) {
    return samePrefix(v6.slice(96), ipv4Bits("127.0.0.0"), 8);
  }
  return samePrefix(v6, ipv6Bits("::1"), 128);
}

function isTooBroad(network) {
  return network.bits.length === 32
    ? network.prefix < MIN_IPV4_PREFIX
    : network.prefix < MIN_IPV6_PREFIX;
}

/** True when the network and 127.0.0.0/8 (or ::1) share at least one address. */
function containsLoopback(network) {
  if (network.bits.length === 32) {
    return samePrefix(network.bits, ipv4Bits("127.0.0.0"), Math.min(network.prefix, 8));
  }
  return samePrefix(network.bits, ipv6Bits("::1"), network.prefix);
}

function samePrefix(a, b, prefix) {
  for (let i = 0; i < prefix; i++) {
    if (a[i] !== b[i]) {
      return false;
    }
  }
  return true;
}

/** @returns {{bits: number[], prefix: number} | null} */
function parseCidr(text) {
  if (typeof text !== "string") {
    return null;
  }
  const slash = text.indexOf("/");
  if (slash <= 0) {
    return null;
  }
  const address = text.slice(0, slash);
  const prefix = Number(text.slice(slash + 1));
  if (!/^\d{1,3}$/.test(text.slice(slash + 1))) {
    return null;
  }
  const bits = address.includes(":") ? ipv6Bits(address) : ipv4Bits(address);
  if (!bits || prefix > bits.length) {
    return null;
  }
  if (bits.length === 128 && isIpv4Mapped(bits) && prefix >= 96) {
    return { bits: bits.slice(96), prefix: prefix - 96 };
  }
  return { bits, prefix };
}

/** ::ffff:0:0/96 — 80 zero bits, then 16 one bits. */
function isIpv4Mapped(bits) {
  for (let i = 0; i < 96; i++) {
    if (bits[i] !== (i < 80 ? 0 : 1)) {
      return false;
    }
  }
  return true;
}

function ipv4Bits(address) {
  const parts = address.split(".");
  if (parts.length !== 4) {
    return null;
  }
  const bits = [];
  for (const part of parts) {
    const value = Number(part);
    if (!/^\d{1,3}$/.test(part) || value > 255) {
      return null;
    }
    for (let i = 7; i >= 0; i--) {
      bits.push((value >> i) & 1);
    }
  }
  return bits;
}

function ipv6Bits(address) {
  const halves = address.split("::");
  if (halves.length > 2) {
    return null;
  }
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const last = halves.length === 2 ? tail : head;
  if (last.length > 0 && last[last.length - 1].includes(".")) {
    // A dotted IPv4 tail (::ffff:10.0.0.1) stands for the last two 16-bit groups.
    const v4 = ipv4Bits(last.pop());
    if (!v4) {
      return null;
    }
    last.push(groupOf(v4.slice(0, 16)), groupOf(v4.slice(16)));
  }
  const missing = 8 - head.length - tail.length;
  if (missing < 0 || (halves.length === 1 && missing !== 0)) {
    return null;
  }
  const groups = [...head, ...Array(missing).fill("0"), ...tail];
  const bits = [];
  for (const group of groups) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(group)) {
      return null;
    }
    const value = parseInt(group, 16);
    for (let i = 15; i >= 0; i--) {
      bits.push((value >> i) & 1);
    }
  }
  return bits;
}

function groupOf(bits) {
  return parseInt(bits.join(""), 2).toString(16);
}
