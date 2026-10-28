/**
 * Spectral custom function: exposes-hosts-binds-ref
 *
 * An exposed adapter's `hosts` entry may be a Mustache expression resolved from the
 * bindings at startup, such as `{{CUSTOM_DOMAIN}}` or `{{deploy.CUSTOM_DOMAIN}}` (issue #893).
 * The value is only known at runtime, so this rule checks what can be checked at lint time:
 * the referenced key (and namespace, when given) must be declared in `binds` or
 * `capability.binds`. The engine checks the resolved value when the capability starts.
 */
const MUSTACHE_PATTERN = /^\{\{\s*(?:([A-Za-z0-9_-]+)\.)?([A-Za-z0-9_-]+)\s*\}\}$/;

// Messages never quote the entry: Spectral would read its double braces as a placeholder.
export default function exposesHostsBindsRef(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};
  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  const bindings = collectBindings(targetVal);
  const results = [];

  for (let i = 0; i < exposes.length; i += 1) {
    const adapter = exposes[i];
    if (!adapter || typeof adapter !== "object" || !Array.isArray(adapter.hosts)) continue;

    for (let h = 0; h < adapter.hosts.length; h += 1) {
      const entry = adapter.hosts[h];
      if (typeof entry !== "string" || !entry.includes("{{")) continue;

      const match = entry.match(MUSTACHE_PATTERN);
      const path = ["capability", "exposes", i, "hosts", h];
      if (!match) {
        results.push({
          message:
            "hosts[" + h + "] must be a host name or a single Mustache expression naming " +
            "one binding key, such as CUSTOM_DOMAIN or deploy.CUSTOM_DOMAIN in double braces.",
          path,
        });
        continue;
      }

      const ns = match[1];
      const key = match[2];
      if (ns !== undefined) {
        const keys = bindings.byNamespace.get(ns);
        if (!keys) {
          results.push({
            message:
              "hosts[" + h + "] references binding namespace '" + ns +
              "' but no matching entry exists in 'binds' or 'capability.binds'.",
            path,
          });
        } else if (!keys.has(key)) {
          results.push({
            message:
              "hosts[" + h + "] references key '" + key + "' on binding '" + ns +
              "', but that key is not declared. Add it to binds[" + ns + "].keys.",
            path,
          });
        }
      } else if (!bindings.allKeys.has(key)) {
        results.push({
          message:
            "hosts[" + h + "] references key '" + key +
            "', but no binding declares it. Add it to the keys of an entry in 'binds'.",
          path,
        });
      }
    }
  }

  return results;
}

function collectBindings(doc) {
  const result = { byNamespace: new Map(), allKeys: new Set() };
  addBindings(doc.binds, result);
  if (doc.capability && typeof doc.capability === "object") {
    addBindings(doc.capability.binds, result);
  }
  return result;
}

function addBindings(binds, result) {
  if (!Array.isArray(binds)) {
    return;
  }
  for (const bind of binds) {
    if (!bind || typeof bind !== "object" || typeof bind.namespace !== "string") {
      continue;
    }
    const keys = result.byNamespace.get(bind.namespace) || new Set();
    if (bind.keys && typeof bind.keys === "object") {
      for (const k of Object.keys(bind.keys)) {
        keys.add(k);
        result.allKeys.add(k);
      }
    }
    result.byNamespace.set(bind.namespace, keys);
  }
}
