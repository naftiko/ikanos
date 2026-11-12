/**
 * Spectral custom function: cache-hints
 *
 * Checks the cache settings of exposed adapters (`ttlMs`, `cacheScope`,
 * `ttlFromUpstream`) against what the rest of the capability declares, mirroring
 * how the engine applies them (#1022). One function serves two rules, selected by
 * the `check` option:
 *
 * - `caller-dependent`: an effective `cacheScope: public` where the answer can
 *   depend on the caller. MCP: the adapter authenticates its callers or identifies
 *   them through `trust`. REST: on a `GET` operation that sends `Cache-Control`
 *   (a positive `ttlMs` on the operation or the adapter), the adapter authenticates
 *   its callers or an input is read from outside the URL (`in: header`, or the
 *   body, where an input without `in` is read). It can be intended, so it warns.
 * - `ttl-from-upstream-no-effect`: `ttlFromUpstream: true` on an MCP resource
 *   whose read does not come from a single HTTP answer: a static `location`,
 *   `steps`, or a call to an MCP consumed adapter. (A resource cannot call an
 *   aggregate flow: `ikanos-exposes-call-not-flow` already reports that.)
 *
 * Walks both `capability.*` and root-level sections (shared section documents).
 * Keyed maps and arrays are both accepted for robustness.
 */
const URL_LOCATIONS = new Set(["path", "query"]);

export default function cacheHints(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = options && options.check;
  const capability = targetVal.capability && typeof targetVal.capability === "object"
    ? targetVal.capability : {};
  const context = {
    consumes: [...asArray(targetVal.consumes), ...asArray(capability.consumes)],
  };
  const results = [];
  scan(targetVal.exposes, ["exposes"], check, context, results);
  scan(capability.exposes, ["capability", "exposes"], check, context, results);
  return results;
}

function scan(exposes, basePath, check, context, results) {
  asArray(exposes).forEach((adapter, index) => {
    if (!adapter || typeof adapter !== "object") {
      return;
    }
    const path = [...basePath, index];
    if (check === "caller-dependent") {
      if (adapter.type === "rest") {
        restCallerDependent(adapter, path, results);
      } else if (adapter.type === "mcp") {
        mcpCallerDependent(adapter, path, results);
      }
    } else if (check === "ttl-from-upstream-no-effect" && adapter.type === "mcp") {
      mcpTtlFromUpstream(adapter, path, context, results);
    }
  });
}

function restCallerDependent(adapter, path, results) {
  const adapterInput = inputOutsideUrl(adapter.inputParameters);
  for (const [resKey, resource] of entries(adapter.resources)) {
    const resourceInput = inputOutsideUrl(resource && resource.inputParameters);
    for (const [opKey, operation] of entries(resource && resource.operations)) {
      if (!operation || String(operation.method).toUpperCase() !== "GET") {
        continue;
      }
      const ttlMs = operation.ttlMs !== undefined ? operation.ttlMs : adapter.ttlMs;
      const ownScope = operation.cacheScope !== undefined;
      const scope = ownScope ? operation.cacheScope : adapter.cacheScope;
      if (scope !== "public" || !(ttlMs > 0)) {
        continue;
      }
      const input = inputOutsideUrl(operation.inputParameters) || resourceInput || adapterInput;
      if (!adapter.authentication && !input) {
        continue;
      }
      const why = adapter.authentication
        ? "the adapter authenticates its callers"
        : "input '" + input + "' is read from outside the URL";
      results.push({
        message: "REST operation '" + opKey + "' answers are cacheScope public"
          + (ownScope ? "" : " (from the adapter)") + ", but " + why
          + ". Check that no answer depends on the caller, or use auto.",
        path: ownScope
          ? [...path, "resources", resKey, "operations", opKey, "cacheScope"]
          : [...path, "cacheScope"],
      });
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

function mcpTtlFromUpstream(adapter, path, context, results) {
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
      reason = callTargetReason(resource.call, context);
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

/** Why a `namespace.operation` call yields no upstream freshness, or null when it can. */
function callTargetReason(call, context) {
  const namespace = call.split(".")[0];
  const consumed = context.consumes.find((c) => c && c.namespace === namespace);
  return consumed && consumed.type === "mcp"
    ? "MCP upstreams send no HTTP caching headers" : null;
}

/** Name of the first input read from outside the URL (header or body), or null. */
function inputOutsideUrl(inputParameters) {
  for (const [key, param] of entries(inputParameters)) {
    if (!param || typeof param !== "object") {
      continue;
    }
    const location = typeof param.in === "string" ? param.in.toLowerCase() : "body";
    if (!URL_LOCATIONS.has(location)) {
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
