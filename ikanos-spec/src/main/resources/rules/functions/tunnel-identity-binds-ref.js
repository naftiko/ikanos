/**
 * Spectral custom function: tunnel-identity-binds-ref
 *
 * Enforces the Reverse Tunnel blueprint rule `ikanos-tunnel-identity-must-bind`:
 * when a `ConsumesHttp.tunnel.identity` field contains one or more Mustache
 * references of the form `{{namespace.key}}`, every referenced namespace must
 * be declared in `binds` or `capability.binds` and the corresponding key must
 * exist on that binding.
 *
 * Bare filesystem paths (no Mustache markers) are accepted without further
 * checking — they are discouraged but not invalid, and the engine resolves
 * them at runtime.
 */
const MUSTACHE_PATTERN = /\{\{\s*([a-z0-9-]+)\.([a-z0-9-_]+)\s*\}\}/gi;

export default function tunnelIdentityBindsRef(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const bindings = collectBindings(targetVal);
  const results = [];

  scanConsumes(targetVal.consumes, ["consumes"], bindings, results);
  if (targetVal.capability && typeof targetVal.capability === "object") {
    scanConsumes(
      targetVal.capability.consumes,
      ["capability", "consumes"],
      bindings,
      results
    );
  }

  return results;
}

function collectBindings(doc) {
  // Map<namespace, Set<key>>
  const map = new Map();
  addBindings(doc.binds, map);
  if (doc.capability && typeof doc.capability === "object") {
    addBindings(doc.capability.binds, map);
  }
  return map;
}

function addBindings(binds, map) {
  if (!Array.isArray(binds)) {
    return;
  }
  for (const bind of binds) {
    if (!bind || typeof bind !== "object" || typeof bind.namespace !== "string") {
      continue;
    }
    const keys = map.get(bind.namespace) || new Set();
    if (bind.keys && typeof bind.keys === "object") {
      for (const k of Object.keys(bind.keys)) {
        keys.add(k);
      }
    }
    map.set(bind.namespace, keys);
  }
}

function scanConsumes(consumes, basePath, bindings, results) {
  if (!Array.isArray(consumes)) {
    return;
  }
  for (let i = 0; i < consumes.length; i += 1) {
    const entry = consumes[i];
    if (!entry || typeof entry !== "object") {
      continue;
    }
    const tunnel = entry.tunnel;
    if (!tunnel || typeof tunnel !== "object" || typeof tunnel.identity !== "string") {
      continue;
    }

    const matches = [...tunnel.identity.matchAll(MUSTACHE_PATTERN)];
    if (matches.length === 0) {
      continue; // bare path or empty — out of scope for this rule
    }

    for (const m of matches) {
      const ns = m[1];
      const key = m[2];
      const keys = bindings.get(ns);
      if (!keys) {
        results.push({
          message:
            "tunnel.identity references binding namespace '" +
            ns +
            "' but no matching entry exists in 'binds' or 'capability.binds'.",
          path: [...basePath, i, "tunnel", "identity"],
        });
      } else if (!keys.has(key)) {
        results.push({
          message:
            "tunnel.identity references key '" +
            key +
            "' on binding '" +
            ns +
            "', but that key is not declared. Add it to binds[" +
            ns +
            "].keys.",
          path: [...basePath, i, "tunnel", "identity"],
        });
      }
    }
  }
}
