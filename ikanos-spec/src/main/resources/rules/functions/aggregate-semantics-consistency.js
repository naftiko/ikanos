export default function aggregateSemanticsConsistency(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};

  const aggregates = Array.isArray(capability.aggregates)
    ? capability.aggregates
    : [];

  if (aggregates.length === 0) {
    return;
  }

  // Build function index: "namespace.function-name" → semantics
  const functionIndex = new Map();
  for (let i = 0; i < aggregates.length; i += 1) {
    const agg = aggregates[i];
    if (!agg || typeof agg.namespace !== "string") {
      continue;
    }
    const functions = Array.isArray(agg.functions) ? agg.functions : [];
    for (let j = 0; j < functions.length; j += 1) {
      const fn = functions[j];
      if (!fn || typeof fn.name !== "string") {
        continue;
      }
      const key = agg.namespace + "." + fn.name;
      functionIndex.set(key, {
        semantics: fn.semantics && typeof fn.semantics === "object" ? fn.semantics : null,
        aggIndex: i,
        fnIndex: j,
      });
    }
  }

  const results = [];
  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];

  for (let e = 0; e < exposes.length; e += 1) {
    const adapter = exposes[e];
    if (!adapter || typeof adapter !== "object") {
      continue;
    }

    if (adapter.type === "mcp") {
      checkMcpTools(adapter, e, functionIndex, results);
    } else if (adapter.type === "rest") {
      checkRestOperations(adapter, e, functionIndex, results);
    }
  }

  return results;
}

function checkMcpTools(adapter, adapterIndex, functionIndex, results) {
  const tools = Array.isArray(adapter.tools) ? adapter.tools : [];

  for (let t = 0; t < tools.length; t += 1) {
    const tool = tools[t];
    if (!tool || typeof tool.ref !== "string") {
      continue;
    }

    const entry = functionIndex.get(tool.ref);
    if (!entry || !entry.semantics) {
      continue;
    }

    const semantics = entry.semantics;
    const hints = tool.hints && typeof tool.hints === "object" ? tool.hints : null;
    if (!hints) {
      continue;
    }

    const basePath = [
      "capability", "exposes", adapterIndex, "tools", t, "hints",
    ];

    // safe vs readOnly
    if (semantics.safe === true && hints.readOnly === false) {
      results.push({
        message:
          "Function '" + tool.ref + "' has semantics.safe=true but tool hints set readOnly=false. Safe functions should be read-only.",
        path: basePath.concat("readOnly"),
      });
    }
    if (semantics.safe === false && hints.readOnly === true) {
      results.push({
        message:
          "Function '" + tool.ref + "' has semantics.safe=false but tool hints set readOnly=true. Unsafe functions should not be read-only.",
        path: basePath.concat("readOnly"),
      });
    }

    // safe vs destructive
    if (semantics.safe === true && hints.destructive === true) {
      results.push({
        message:
          "Function '" + tool.ref + "' has semantics.safe=true but tool hints set destructive=true. Safe functions should not be destructive.",
        path: basePath.concat("destructive"),
      });
    }

    // idempotent consistency
    if (semantics.idempotent === true && hints.idempotent === false) {
      results.push({
        message:
          "Function '" + tool.ref + "' has semantics.idempotent=true but tool hints set idempotent=false.",
        path: basePath.concat("idempotent"),
      });
    }
    if (semantics.idempotent === false && hints.idempotent === true) {
      results.push({
        message:
          "Function '" + tool.ref + "' has semantics.idempotent=false but tool hints set idempotent=true.",
        path: basePath.concat("idempotent"),
      });
    }
  }
}

function checkRestOperations(adapter, adapterIndex, functionIndex, results) {
  const resources = Array.isArray(adapter.resources) ? adapter.resources : [];

  for (let r = 0; r < resources.length; r += 1) {
    const resource = resources[r];
    if (!resource || typeof resource !== "object") {
      continue;
    }

    const operations = Array.isArray(resource.operations)
      ? resource.operations
      : [];

    for (let o = 0; o < operations.length; o += 1) {
      const op = operations[o];
      if (!op || typeof op.ref !== "string" || typeof op.method !== "string") {
        continue;
      }

      const entry = functionIndex.get(op.ref);
      if (!entry || !entry.semantics) {
        continue;
      }

      const semantics = entry.semantics;
      const method = op.method.toUpperCase();
      const basePath = [
        "capability", "exposes", adapterIndex, "resources", r, "operations", o, "method",
      ];

      // safe vs mutating methods
      if (semantics.safe === true && method !== "GET") {
        results.push({
          message:
            "Function '" + op.ref + "' has semantics.safe=true but REST operation uses " + method + ". Safe functions should use GET.",
          path: basePath,
        });
      }
      if (semantics.safe === false && method === "GET") {
        results.push({
          message:
            "Function '" + op.ref + "' has semantics.safe=false but REST operation uses GET. Unsafe functions should not use GET.",
          path: basePath,
        });
      }

      // idempotent vs POST
      if (semantics.idempotent === true && method === "POST") {
        results.push({
          message:
            "Function '" + op.ref + "' has semantics.idempotent=true but REST operation uses POST. POST is not idempotent by convention.",
          path: basePath,
        });
      }
    }
  }
}
