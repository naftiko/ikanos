/**
 * Spectral custom function: aggregate-semantics-consistency
 *
 * Verifies that explicit MCP tool hints and REST operation methods do not
 * contradict the declared semantics of the aggregate flow they reference.
 *
 * In phase-2 / phase-3 of the unified import mechanism, several collections
 * were rescoped from arrays to keyed maps:
 *
 *   - aggregate.flows         (was: aggregate.functions, an array)
 *   - mcpAdapter.tools        (was: an array)
 *   - restResource.operations (was: an array)
 *   - restAdapter.resources   (was: an array)
 *
 * This function accepts BOTH shapes so it keeps working with the older
 * standalone documents that still emit arrays, as well as the modern
 * keyed-map documents.
 *
 * Naming rules (modern keyed-map form):
 *   - The map key is the canonical name (e.g. `flows.get-forecast` → name
 *     "get-forecast").
 *   - When still in array form, the per-entry `name` field is used.
 *   - For aggregates themselves, the canonical identifier remains the
 *     `namespace` field, regardless of array or map outer shape.
 */
export default function aggregateSemanticsConsistency(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};

  const aggregates = toAggregateEntries(capability.aggregates);
  if (aggregates.length === 0) {
    return;
  }

  // Build flow index: "namespace.flow-name" → { semantics }
  const flowIndex = new Map();
  for (const aggEntry of aggregates) {
    const agg = aggEntry.value;
    if (!agg || typeof agg.namespace !== "string") {
      continue;
    }
    const flows = toNamedEntries(agg.flows, agg.functions);
    for (const flowEntry of flows) {
      const fn = flowEntry.value;
      if (!fn || typeof fn !== "object") {
        continue;
      }
      const key = agg.namespace + "." + flowEntry.name;
      flowIndex.set(key, {
        semantics:
          fn.semantics && typeof fn.semantics === "object" ? fn.semantics : null,
      });
    }
  }

  const results = [];
  const exposes = toIndexedEntries(capability.exposes);

  for (const adapterEntry of exposes) {
    const adapter = adapterEntry.value;
    if (!adapter || typeof adapter !== "object") {
      continue;
    }

    if (adapter.type === "mcp") {
      checkMcpTools(adapter, adapterEntry.locator, flowIndex, results);
    } else if (adapter.type === "rest") {
      checkRestOperations(adapter, adapterEntry.locator, flowIndex, results);
    }
  }

  return results;
}

function checkMcpTools(adapter, adapterLocator, flowIndex, results) {
  const tools = toNamedEntries(adapter.tools, null);

  for (const toolEntry of tools) {
    const tool = toolEntry.value;
    if (!tool || typeof tool.ref !== "string") {
      continue;
    }

    const entry = flowIndex.get(tool.ref);
    if (!entry || !entry.semantics) {
      continue;
    }

    const semantics = entry.semantics;
    const hints = tool.hints && typeof tool.hints === "object" ? tool.hints : null;
    if (!hints) {
      continue;
    }

    const basePath = [
      "capability", "exposes", ...adapterLocator, "tools", ...toolEntry.locator, "hints",
    ];

    // safe vs readOnly
    if (semantics.safe === true && hints.readOnly === false) {
      results.push({
        message:
          "Flow '" + tool.ref + "' has semantics.safe=true but tool hints set readOnly=false. Safe flows should be read-only.",
        path: basePath.concat("readOnly"),
      });
    }
    if (semantics.safe === false && hints.readOnly === true) {
      results.push({
        message:
          "Flow '" + tool.ref + "' has semantics.safe=false but tool hints set readOnly=true. Unsafe flows should not be read-only.",
        path: basePath.concat("readOnly"),
      });
    }

    // safe vs destructive
    if (semantics.safe === true && hints.destructive === true) {
      results.push({
        message:
          "Flow '" + tool.ref + "' has semantics.safe=true but tool hints set destructive=true. Safe flows should not be destructive.",
        path: basePath.concat("destructive"),
      });
    }

    // idempotent consistency
    if (semantics.idempotent === true && hints.idempotent === false) {
      results.push({
        message:
          "Flow '" + tool.ref + "' has semantics.idempotent=true but tool hints set idempotent=false.",
        path: basePath.concat("idempotent"),
      });
    }
    if (semantics.idempotent === false && hints.idempotent === true) {
      results.push({
        message:
          "Flow '" + tool.ref + "' has semantics.idempotent=false but tool hints set idempotent=true.",
        path: basePath.concat("idempotent"),
      });
    }
  }
}

function checkRestOperations(adapter, adapterLocator, flowIndex, results) {
  const resources = toNamedEntries(adapter.resources, null);

  for (const resourceEntry of resources) {
    const resource = resourceEntry.value;
    if (!resource || typeof resource !== "object") {
      continue;
    }

    const operations = toNamedEntries(resource.operations, null);

    for (const opEntry of operations) {
      const op = opEntry.value;
      if (!op || typeof op.ref !== "string" || typeof op.method !== "string") {
        continue;
      }

      const entry = flowIndex.get(op.ref);
      if (!entry || !entry.semantics) {
        continue;
      }

      const semantics = entry.semantics;
      const method = op.method.toUpperCase();
      const basePath = [
        "capability", "exposes", ...adapterLocator,
        "resources", ...resourceEntry.locator,
        "operations", ...opEntry.locator,
        "method",
      ];

      // safe vs mutating methods
      if (semantics.safe === true && method !== "GET") {
        results.push({
          message:
            "Flow '" + op.ref + "' has semantics.safe=true but REST operation uses " + method + ". Safe flows should use GET.",
          path: basePath,
        });
      }
      if (semantics.safe === false && method === "GET") {
        results.push({
          message:
            "Flow '" + op.ref + "' has semantics.safe=false but REST operation uses GET. Unsafe flows should not use GET.",
          path: basePath,
        });
      }

      // idempotent vs POST
      if (semantics.idempotent === true && method === "POST") {
        results.push({
          message:
            "Flow '" + op.ref + "' has semantics.idempotent=true but REST operation uses POST. POST is not idempotent by convention.",
          path: basePath,
        });
      }
    }
  }
}

// ----------------------------------------------------------------------
// Shape adapters: accept BOTH array and keyed-map shapes uniformly.
// ----------------------------------------------------------------------

/**
 * Walks an indexed collection (always an array per schema). Each returned
 * entry has a `locator` of `[index]` so callers can build JSON pointer
 * paths the same way for arrays and maps.
 */
function toIndexedEntries(arr) {
  if (!Array.isArray(arr)) {
    return [];
  }
  const out = [];
  for (let i = 0; i < arr.length; i += 1) {
    out.push({ value: arr[i], locator: [i] });
  }
  return out;
}

/**
 * Walks a keyed-map collection (modern phase-2 form) OR a fallback array
 * (legacy form using a `name` field per entry). Each returned entry has:
 *   - `name`    — string identifier (map key, or entry.name for arrays)
 *   - `value`   — the entry value
 *   - `locator` — JSON-pointer segments to reach the entry from the
 *                 collection root (e.g. `[mapKey]` or `[arrayIndex]`)
 *
 * `primary` is the modern keyed-map field (e.g. `flows`). `legacy` is the
 * older array field name (e.g. `functions`); pass null when there is no
 * legacy form.
 */
function toNamedEntries(primary, legacy) {
  const out = [];
  if (primary && typeof primary === "object" && !Array.isArray(primary)) {
    for (const key of Object.keys(primary)) {
      out.push({ name: key, value: primary[key], locator: [key] });
    }
    return out;
  }
  if (Array.isArray(primary)) {
    for (let i = 0; i < primary.length; i += 1) {
      const v = primary[i];
      const name = v && typeof v === "object" && typeof v.name === "string" ? v.name : null;
      if (name !== null) {
        out.push({ name, value: v, locator: [i] });
      }
    }
    return out;
  }
  if (Array.isArray(legacy)) {
    for (let i = 0; i < legacy.length; i += 1) {
      const v = legacy[i];
      const name = v && typeof v === "object" && typeof v.name === "string" ? v.name : null;
      if (name !== null) {
        out.push({ name, value: v, locator: [i] });
      }
    }
  }
  return out;
}

/**
 * `capability.aggregates` is documented as an array of inline-aggregate /
 * import-entry objects. However, some older fixtures (and a possible
 * future migration to a fully keyed-map form) may emit it as a map keyed
 * by namespace. Accept both, and derive the namespace from the entry
 * itself rather than the key (matches the rest of the rule's logic).
 */
function toAggregateEntries(aggregates) {
  if (Array.isArray(aggregates)) {
    return aggregates.map((value, index) => ({ value, locator: [index] }));
  }
  if (aggregates && typeof aggregates === "object") {
    return Object.keys(aggregates).map((key) => ({
      value: aggregates[key],
      locator: [key],
    }));
  }
  return [];
}
