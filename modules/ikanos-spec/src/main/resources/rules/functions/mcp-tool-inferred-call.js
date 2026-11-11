/**
 * Spectral custom function: mcp-tool-inferred-call
 *
 * An exposed MCP tool that declares none of `call`, `steps`, `ref` or mock
 * `outputParameters` (a map, or an array of static `value`s only) calls the
 * consumed operation with the same name as the tool (#988). The candidates
 * are the operations of the consumed HTTP adapters and the tools of the
 * consumed MCP adapters. Exactly
 * one consumed namespace must have one; this function reports a tool for which
 * none or several do.
 *
 * A consumes import (`import:` / `as:`) keeps its operations in another
 * document, out of reach of a single-document lint: when the capability has
 * one, a tool without a candidate is not reported. The engine checks it when
 * the capability loads.
 */
export default function mcpToolInferredCall(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : null;
  if (!capability) {
    return;
  }

  const operations = new Map(); // name -> [namespace, ...]
  let hasImports = false;
  for (const c of entries(capability.consumes)) {
    const consumed = c.value;
    if (consumed && typeof consumed.import === "string") {
      hasImports = true;
      continue;
    }
    if (!consumed || typeof consumed.namespace !== "string") {
      continue;
    }
    const names = new Set();
    if (consumed.type === "mcp") {
      for (const t of entries(consumed.tools)) {
        names.add(t.key);
      }
    } else if (consumed.type === "http") {
      for (const r of entries(consumed.resources)) {
        for (const o of entries(r.value && r.value.operations)) {
          names.add(o.key);
        }
      }
    }
    for (const name of names) {
      if (!operations.has(name)) {
        operations.set(name, []);
      }
      operations.get(name).push(consumed.namespace);
    }
  }

  const results = [];
  for (const a of entries(capability.exposes)) {
    const adapter = a.value;
    if (!adapter || adapter.type !== "mcp") {
      continue;
    }
    for (const t of entries(adapter.tools)) {
      const tool = t.value;
      if (!tool || typeof tool !== "object" || !needsInferredCall(tool)) {
        continue;
      }
      const namespaces = operations.get(t.key) || [];
      const path = ["capability", "exposes", ...a.locator, "tools", ...t.locator];
      if (namespaces.length === 0 && !hasImports) {
        results.push({
          message: "MCP tool '" + t.key + "' has no 'call', so it calls the consumed operation named '"
            + t.key + "', but no consumed namespace has one. Add 'call: <namespace>.<operation>'.",
          path,
        });
      } else if (namespaces.length > 1) {
        results.push({
          message: "MCP tool '" + t.key + "' has no 'call', so it calls the consumed operation named '"
            + t.key + "', but several consumed namespaces have one: "
            + namespaces.map((ns) => "'" + ns + "'").join(", ") + ". Add 'call:' to choose.",
          path,
        });
      }
    }
  }
  return results;
}

function needsInferredCall(tool) {
  if (tool.call !== undefined || tool.steps !== undefined || tool.ref !== undefined) {
    return false;
  }
  const outputs = tool.outputParameters;
  if (Array.isArray(outputs)) {
    // An array of static values only is run as a mock, not as mapped outputs.
    return !(outputs.length > 0 && outputs.every((o) => o && typeof o === "object"
      && o.value !== undefined && o.mapping === undefined));
  }
  return !(outputs && typeof outputs === "object" && Object.keys(outputs).length > 0);
}

function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, index) => ({
      key: value && typeof value.name === "string" ? value.name : String(index),
      value,
      locator: [index],
    }));
  }
  if (collection && typeof collection === "object") {
    return Object.keys(collection).map((key) => ({
      key,
      value: collection[key],
      locator: [key],
    }));
  }
  return [];
}
