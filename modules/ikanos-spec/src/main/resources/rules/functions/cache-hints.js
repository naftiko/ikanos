/**
 * Spectral custom function: cache-hints
 *
 * Checks the cache settings of exposed adapters (`ttlMs`, `cacheScope`,
 * `ttlFromUpstream`) against what the rest of the capability declares. One function
 * serves three rules, selected by the `check` option:
 *
 * - `forwards-credentials`: a REST adapter sends `public` answers from a resource
 *   that forwards the caller's credentials upstream. A shared cache would then
 *   serve one caller's data to another (#1022).
 * - `caller-dependent`: an explicit `cacheScope: public` where the answer can
 *   depend on the caller: the adapter authenticates its callers (or, for MCP,
 *   identifies them through `trust`), or a REST operation reads an input from a
 *   request header or cookie. It can be intended, so it is a warning.
 * - `ttl-from-upstream-no-effect`: `ttlFromUpstream: true` on an MCP resource
 *   whose read does not come from a single HTTP call (static `location`,
 *   `steps`, or a call to an MCP consumed adapter), where it has no effect.
 *
 * Walks both `capability.exposes` and root-level `exposes` (shared section
 * documents). Keyed maps and arrays are both accepted for robustness.
 */
const CREDENTIAL_HEADERS = new Set(["authorization", "cookie", "proxy-authorization"]);

export default function cacheHints(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = options && options.check;
  const results = [];
  const consumes = [
    ...asArray(targetVal.consumes),
    ...asArray(targetVal.capability && targetVal.capability.consumes),
  ];
  scan(targetVal.exposes, ["exposes"], check, consumes, results);
  if (targetVal.capability && typeof targetVal.capability === "object") {
    scan(targetVal.capability.exposes, ["capability", "exposes"], check, consumes, results);
  }
  return results;
}

function scan(exposes, basePath, check, consumes, results) {
  asArray(exposes).forEach((adapter, index) => {
    if (!adapter || typeof adapter !== "object") {
      return;
    }
    const path = [...basePath, index];
    if (adapter.type === "rest") {
      if (check === "forwards-credentials") {
        restForwardsCredentials(adapter, path, results);
      } else if (check === "caller-dependent") {
        restCallerDependent(adapter, path, results);
      }
    } else if (adapter.type === "mcp") {
      if (check === "caller-dependent") {
        mcpCallerDependent(adapter, path, results);
      } else if (check === "ttl-from-upstream-no-effect") {
        mcpTtlFromUpstream(adapter, path, consumes, results);
      }
    }
  });
}

function restForwardsCredentials(adapter, path, results) {
  if (adapter.cacheScope !== "public" || !(adapter.ttlMs > 0)) {
    return;
  }
  for (const [key, resource] of entries(adapter.resources)) {
    const forward = resource && resource.forward;
    const forwarded = asArray(forward && forward.trustedHeaders)
      .filter((h) => typeof h === "string" && CREDENTIAL_HEADERS.has(h.toLowerCase()));
    if (forwarded.length > 0) {
      results.push({
        message:
          "REST resource '" + key + "' forwards " + forwarded.join(", ")
          + " upstream, but the adapter sends its answers as cacheScope public: a shared "
          + "cache would serve one caller's data to another. Use private or auto.",
        path: [...path, "cacheScope"],
      });
    }
  }
}

function restCallerDependent(adapter, path, results) {
  const authenticated = Boolean(adapter.authentication);
  const adapterHeaderInput = headerInput(adapter.inputParameters);
  if (adapter.cacheScope === "public" && (authenticated || adapterHeaderInput)) {
    results.push({
      message: "REST adapter answers are cacheScope public, but "
        + (authenticated ? "the adapter authenticates its callers"
          : "an input is read from a request header or cookie (" + adapterHeaderInput + ")")
        + ". Check that no answer depends on the caller, or use auto.",
      path: [...path, "cacheScope"],
    });
  }
  for (const [resKey, resource] of entries(adapter.resources)) {
    const resourceHeaderInput = headerInput(resource && resource.inputParameters);
    for (const [opKey, operation] of entries(resource && resource.operations)) {
      if (!operation || operation.cacheScope !== "public") {
        continue;
      }
      const input = headerInput(operation.inputParameters) || resourceHeaderInput
        || adapterHeaderInput;
      if (authenticated || input) {
        results.push({
          message: "REST operation '" + opKey + "' answers are cacheScope public, but "
            + (authenticated ? "the adapter authenticates its callers"
              : "an input is read from a request header or cookie (" + input + ")")
            + ". Check that no answer depends on the caller, or use auto.",
          path: [...path, "resources", resKey, "operations", opKey, "cacheScope"],
        });
      }
    }
  }
}

function mcpCallerDependent(adapter, path, results) {
  const trustMode = adapter.trust && adapter.trust.mode;
  const identified = trustMode && trustMode !== "none";
  if (!adapter.authentication && !identified) {
    return;
  }
  const why = adapter.authentication
    ? "the adapter authenticates its callers"
    : "callers are identified (trust: " + trustMode + ")";
  if (adapter.cacheScope === "public") {
    results.push({
      message: "MCP adapter results are cacheScope public, but " + why
        + ". Check that no result depends on the caller, or use auto.",
      path: [...path, "cacheScope"],
    });
  }
  for (const [key, resource] of entries(adapter.resources)) {
    if (resource && resource.cacheScope === "public") {
      results.push({
        message: "MCP resource '" + key + "' is cacheScope public, but " + why
          + ". Check that no result depends on the caller, or use auto.",
        path: [...path, "resources", key, "cacheScope"],
      });
    }
  }
}

function mcpTtlFromUpstream(adapter, path, consumes, results) {
  for (const [key, resource] of entries(adapter.resources)) {
    if (!resource || resource.ttlFromUpstream !== true) {
      continue;
    }
    let reason = null;
    if (resource.location) {
      reason = "a static resource has no upstream";
    } else if (resource.steps) {
      reason = "with steps, no single upstream answer speaks for the result";
    } else if (typeof resource.call === "string") {
      const namespace = resource.call.split(".")[0];
      const target = consumes.find((c) => c && c.namespace === namespace);
      if (target && target.type === "mcp") {
        reason = "MCP upstreams send no HTTP caching headers";
      }
    }
    if (reason) {
      results.push({
        message: "ttlFromUpstream has no effect on MCP resource '" + key + "': " + reason
          + ". Set ttlMs instead.",
        path: [...path, "resources", key, "ttlFromUpstream"],
      });
    }
  }
}

/** Name of the first input read from a header or cookie, or null. */
function headerInput(inputParameters) {
  for (const [key, param] of entries(inputParameters)) {
    if (param && (param.in === "header" || param.in === "cookie")) {
      return param.name || key;
    }
  }
  return null;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, i) => [i, value]);
  }
  if (collection && typeof collection === "object") {
    return Object.entries(collection);
  }
  return [];
}
