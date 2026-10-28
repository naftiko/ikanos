/**
 * Spectral custom function: shared-listener-mounts
 *
 * Exposed adapters on the same address and port share one listener and are routed by
 * `hosts`, then by `path` (issue #893). The engine refuses a capability whose mounts are
 * ambiguous; this rule reports the same problems at lint time:
 *
 * - two adapters on the same path of the same host (two adapters without `path` and
 *   `hosts` on one port included);
 * - a path nested inside another one on the same host, such as `/api` and `/api/v2`.
 *
 * Adapters without `hosts` answer on any host. A `hosts` entry written as a Mustache
 * expression is compared as written, since its value is only known at startup. The control
 * adapter is checked by `control-port-validation`, and MCP over stdio has no listener.
 */
export default function sharedListenerMounts(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};
  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];

  // Map<listener, Map<host, Array<{ index, path, label }>>>
  const listeners = new Map();
  for (let i = 0; i < exposes.length; i += 1) {
    const adapter = exposes[i];
    if (!adapter || typeof adapter !== "object" || !adapter.port) continue;
    if (adapter.type === "control") continue;
    if (adapter.type === "mcp" && adapter.transport === "stdio") continue;

    const address =
      typeof adapter.address === "string" ? adapter.address.toLowerCase() : "localhost";
    const listener = address + ":" + adapter.port;
    const path = typeof adapter.path === "string" && adapter.path !== "/" ? adapter.path : "";
    const label =
      adapter.type + " adapter" + (adapter.namespace ? " '" + adapter.namespace + "'" : "");
    const hosts =
      Array.isArray(adapter.hosts) && adapter.hosts.length > 0
        ? adapter.hosts.map((h) => (typeof h === "string" ? h.toLowerCase() : String(h)))
        : ["*"];

    const byHost = listeners.get(listener) || new Map();
    for (const host of new Set(hosts)) {
      const mounts = byHost.get(host) || [];
      mounts.push({ index: i, path, label });
      byHost.set(host, mounts);
    }
    listeners.set(listener, byHost);
  }

  const results = [];
  const reported = new Set();
  for (const [listener, byHost] of listeners) {
    for (const [host, mounts] of byHost) {
      const where = (host === "*" ? "any host" : "host '" + host + "'") + " of " + listener;
      for (let a = 0; a < mounts.length; a += 1) {
        for (let b = a + 1; b < mounts.length; b += 1) {
          const first = mounts[a];
          const second = mounts[b];
          let message = null;
          if (first.path === second.path) {
            message =
              first.label +
              " and " +
              second.label +
              " are both mounted at path '" +
              (first.path || "/") +
              "' on " +
              where +
              ". Give each adapter a distinct 'path' or 'hosts'.";
          } else if (isNested(first.path, second.path) || isNested(second.path, first.path)) {
            message =
              first.label +
              " at path '" +
              (first.path || "/") +
              "' and " +
              second.label +
              " at path '" +
              (second.path || "/") +
              "' overlap on " +
              where +
              ". One path must not be a prefix of the other.";
          }
          const key = second.index + ":" + first.index;
          if (message && !reported.has(key)) {
            reported.add(key);
            results.push({ message, path: ["capability", "exposes", second.index] });
          }
        }
      }
    }
  }
  return results;
}

function isNested(outer, inner) {
  return outer !== "" && inner.startsWith(outer + "/");
}
