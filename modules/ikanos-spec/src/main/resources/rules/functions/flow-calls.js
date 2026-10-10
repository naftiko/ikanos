/**
 * Spectral custom function: flow-calls
 *
 * Checks calls between aggregate flows (`call: namespace.flow-name`, from a
 * `call` step or a flow-level `call:`). Select the check with
 * `functionOptions.check`:
 *
 *   - "targets":   a call names an aggregate namespace but no flow of that name;
 *   - "cycles":    flows call each other in a cycle;
 *   - "exposes":   an exposed MCP tool or resource, or REST operation, uses
 *                  `call:` to reach a flow. Exposed units use `ref:`;
 *   - "semantics": a flow declares `safe: true` or `idempotent: true` but calls
 *                  a target that is not. A flow's effective semantics is the
 *                  conjunction of its own declaration and those of every target
 *                  it calls: another flow (its effective semantics), a consumed
 *                  MCP tool (its local `hints`), or a consumed HTTP operation
 *                  (its method, per RFC 9110). A target without a declaration
 *                  counts as neither safe nor idempotent.
 *
 * Call targets are split on the first dot: consumed MCP tools are keyed by
 * their upstream name, which may contain dots.
 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const IDEMPOTENT_METHODS = new Set(["GET", "HEAD", "OPTIONS", "PUT", "DELETE"]);

export default function flowCalls(targetVal, options) {
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

  const model = buildModel(capability);
  if (model.flows.size === 0) {
    return;
  }

  const check = options && options.check;
  if (check === "targets") {
    return checkTargets(model);
  }
  if (check === "cycles") {
    return checkCycles(model);
  }
  if (check === "exposes") {
    return checkExposes(capability, model);
  }
  if (check === "semantics") {
    return checkSemantics(model);
  }
  return;
}

function split(ref) {
  if (typeof ref !== "string") {
    return null;
  }
  const dot = ref.indexOf(".");
  if (dot <= 0 || dot === ref.length - 1) {
    return null;
  }
  return { namespace: ref.substring(0, dot), name: ref.substring(dot + 1) };
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

function buildModel(capability) {
  const flows = new Map(); // "ns.flow" -> { flow, path, calls: [{ ref, path }] }
  const aggregateNamespaces = new Set();
  for (const agg of entries(capability.aggregates)) {
    const aggregate = agg.value;
    if (!aggregate || typeof aggregate.namespace !== "string") {
      continue;
    }
    aggregateNamespaces.add(aggregate.namespace);
    for (const f of entries(aggregate.flows)) {
      const flow = f.value;
      if (!flow || typeof flow !== "object") {
        continue;
      }
      const path = ["capability", "aggregates", ...agg.locator, "flows", ...f.locator];
      const calls = [];
      if (typeof flow.call === "string") {
        calls.push({ ref: flow.call, path: path.concat("call") });
      }
      for (const s of entries(flow.steps)) {
        const step = s.value;
        if (step && step.type === "call" && typeof step.call === "string") {
          calls.push({ ref: step.call, path: path.concat("steps", ...s.locator, "call") });
        }
      }
      flows.set(aggregate.namespace + "." + f.key, { flow, path, calls });
    }
  }

  const mcpTools = new Map(); // "ns.tool" -> hints
  const httpOperations = new Map(); // "ns.op" -> method
  for (const c of entries(capability.consumes)) {
    const consumed = c.value;
    if (!consumed || typeof consumed.namespace !== "string") {
      continue;
    }
    if (consumed.type === "mcp") {
      for (const t of entries(consumed.tools)) {
        mcpTools.set(consumed.namespace + "." + t.key,
          t.value && typeof t.value.hints === "object" ? t.value.hints : null);
      }
    } else if (consumed.type === "http") {
      for (const r of entries(consumed.resources)) {
        for (const o of entries(r.value && r.value.operations)) {
          const op = o.value;
          if (op && typeof op.method === "string") {
            httpOperations.set(consumed.namespace + "." + o.key, op.method.toUpperCase());
          }
        }
      }
    }
  }

  return { flows, aggregateNamespaces, mcpTools, httpOperations };
}

function checkTargets(model) {
  const results = [];
  for (const [key, info] of model.flows) {
    for (const call of info.calls) {
      const target = split(call.ref);
      if (target && model.aggregateNamespaces.has(target.namespace)
          && !model.flows.has(call.ref)) {
        results.push({
          message: "Flow '" + key + "' calls '" + call.ref + "', but aggregate '"
            + target.namespace + "' has no flow '" + target.name + "'.",
          path: call.path,
        });
      }
    }
  }
  return results;
}

function checkCycles(model) {
  const results = [];
  const done = new Set();
  const reported = new Set();

  function visit(node, stack) {
    if (done.has(node)) {
      return;
    }
    const index = stack.indexOf(node);
    if (index >= 0) {
      const cycle = stack.slice(index).concat(node);
      const id = cycle.slice(0, -1).slice().sort().join("|");
      if (!reported.has(id)) {
        reported.add(id);
        results.push({
          message: "Aggregate flows call each other in a cycle: " + cycle.join(" -> ") + ".",
          path: model.flows.get(node).path,
        });
      }
      return;
    }
    const info = model.flows.get(node);
    if (!info) {
      return;
    }
    stack.push(node);
    for (const call of info.calls) {
      if (model.flows.has(call.ref)) {
        visit(call.ref, stack);
      }
    }
    stack.pop();
    done.add(node);
  }

  for (const key of model.flows.keys()) {
    visit(key, []);
  }
  return results;
}

function checkExposes(capability, model) {
  const results = [];
  for (const a of entries(capability.exposes)) {
    const adapter = a.value;
    if (!adapter || typeof adapter !== "object") {
      continue;
    }
    const base = ["capability", "exposes", ...a.locator];
    const units = [];
    if (adapter.type === "mcp") {
      for (const t of entries(adapter.tools)) {
        units.push({ label: "MCP tool '" + t.key + "'", unit: t.value,
          path: base.concat("tools", ...t.locator, "call") });
      }
      for (const r of entries(adapter.resources)) {
        units.push({ label: "MCP resource '" + r.key + "'", unit: r.value,
          path: base.concat("resources", ...r.locator, "call") });
      }
    } else if (adapter.type === "rest") {
      for (const r of entries(adapter.resources)) {
        for (const o of entries(r.value && r.value.operations)) {
          units.push({ label: "REST operation '" + o.key + "'", unit: o.value,
            path: base.concat("resources", ...r.locator, "operations", ...o.locator, "call") });
        }
      }
    }
    for (const u of units) {
      const call = u.unit && u.unit.call;
      const ref = typeof call === "string" ? call
        : call && typeof call.operation === "string" ? call.operation : null;
      const target = split(ref);
      if (target && model.aggregateNamespaces.has(target.namespace)) {
        results.push({
          message: u.label + " calls aggregate flow '" + ref + "' with 'call:'. Use 'ref: "
            + ref + "' to expose an aggregate flow.",
          path: u.path,
        });
      }
    }
  }
  return results;
}

function checkSemantics(model) {
  const memo = new Map();

  function declared(flow, field) {
    return flow.semantics && typeof flow.semantics === "object"
      ? flow.semantics[field] : undefined;
  }

  // Effective value of `field` ("safe" | "idempotent") for a flow: its declaration and every target.
  function effectiveFlow(key, field, visiting) {
    const memoKey = key + "#" + field;
    if (memo.has(memoKey)) {
      return memo.get(memoKey);
    }
    if (visiting.has(key)) {
      return false; // cycles are reported by the "cycles" check
    }
    visiting.add(key);
    const info = model.flows.get(key);
    let value = declared(info.flow, field) === true
      || (field === "idempotent" && declared(info.flow, "safe") === true);
    if (value) {
      for (const call of info.calls) {
        if (!effectiveTarget(call.ref, field, visiting)) {
          value = false;
          break;
        }
      }
    }
    visiting.delete(key);
    memo.set(memoKey, value);
    return value;
  }

  function effectiveTarget(ref, field, visiting) {
    if (model.flows.has(ref)) {
      return effectiveFlow(ref, field, visiting);
    }
    if (model.mcpTools.has(ref)) {
      const hints = model.mcpTools.get(ref) || {};
      const safe = hints.readOnly === true && hints.destructive !== true;
      return field === "safe" ? safe : safe || hints.idempotent === true;
    }
    if (model.httpOperations.has(ref)) {
      const method = model.httpOperations.get(ref);
      return field === "safe" ? SAFE_METHODS.has(method) : IDEMPOTENT_METHODS.has(method);
    }
    return false; // unknown target (for example another consumes type): not proven
  }

  const results = [];
  for (const [key, info] of model.flows) {
    for (const field of ["safe", "idempotent"]) {
      if (declared(info.flow, field) !== true) {
        continue;
      }
      for (const call of info.calls) {
        if (!effectiveTarget(call.ref, field, new Set([key]))) {
          results.push({
            message: "Flow '" + key + "' declares semantics." + field + "=true but calls '"
              + call.ref + "', which is not " + (field === "safe" ? "safe" : "idempotent")
              + ". A flow's semantics cannot be less conservative than its targets'.",
            path: info.path.concat("semantics", field),
          });
        }
      }
    }
  }
  return results;
}
