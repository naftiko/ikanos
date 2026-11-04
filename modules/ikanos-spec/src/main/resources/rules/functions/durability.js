/**
 * Polychro rule function: durability
 *
 * Checks the combination of a capability's durability mode (the control port's
 * `durability.mode`, `none` when absent) and the constructs that need one.
 * Select the check with `functionOptions.check`:
 *
 *   - "task-mode":      a flow declares `semantics.interaction.mode: task` while
 *                       the capability runs under `none`: there is nowhere to
 *                       keep the task;
 *   - "elicit-safety":  under `none`, an `elicit` step follows a call step whose
 *                       target is not read-only. Without a journal, an answer
 *                       replays the steps from the start, so that call would run
 *                       again. A target is read-only when it is a consumed HTTP
 *                       operation with a safe method (RFC 9110), a consumed MCP
 *                       tool with `hints.readOnly: true`, or a flow declaring
 *                       `semantics.safe: true`. Targets in an imported namespace
 *                       are out of reach of a single-document lint and skipped;
 *   - "mode-available": the mode is `local`, whose H2 store module is not
 *                       available yet: the engine rejects the capability;
 *   - "sealing-key-binds": `durability.sealingKey` is not a single Mustache
 *                       expression naming a key declared in `binds` or
 *                       `capability.binds` (`{{KEY}}` or `{{namespace.KEY}}`);
 *   - "elicit-over-rest": a REST operation whose steps, or whose `ref:` or
 *                       `call:` flow (and the flows it calls), include an
 *                       `elicit` step, which REST cannot serve yet.
 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const MUSTACHE_PATTERN = /^\{\{\s*(?:([A-Za-z0-9_-]+)\.)?([A-Za-z0-9_-]+)\s*\}\}$/;

export default function durability(targetVal, options) {
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

  const control = findControl(capability);
  const check = options && options.check;
  if (check === "task-mode") {
    return control.mode === "none" ? checkTaskMode(capability) : [];
  }
  if (check === "elicit-safety") {
    return control.mode === "none" ? checkElicitSafety(capability) : [];
  }
  if (check === "mode-available") {
    if (control.mode === "local") {
      return [{
        message: "Durability mode 'local' is not available yet: its H2 store module ships "
          + "with a later release. Use 'memory' or 'none'.",
        path: control.path.concat("durability", "mode"),
      }];
    }
    return [];
  }
  if (check === "sealing-key-binds") {
    return checkSealingKey(targetVal, control);
  }
  if (check === "elicit-over-rest") {
    return checkElicitOverRest(capability);
  }
  return;
}

// Messages never quote the key: it may be a secret, and the linter could read double braces
// as a placeholder.
function checkSealingKey(doc, control) {
  const durability = control.adapter && control.adapter.durability;
  const declared = durability && durability.sealingKey;
  if (typeof declared !== "string" || declared.trim() === "") {
    return [];
  }
  const path = control.path.concat("durability", "sealingKey");
  const match = declared.match(MUSTACHE_PATTERN);
  if (!match) {
    return [{
      message: "durability.sealingKey is written into the capability. Reference a binding "
        + "key instead, such as IKANOS_SEALING_KEY in double braces, so the key stays out of "
        + "source control.",
      path,
    }];
  }
  const bindings = collectBindings(doc);
  const ns = match[1];
  const key = match[2];
  if (ns !== undefined) {
    const keys = bindings.byNamespace.get(ns);
    if (!keys) {
      return [{
        message: "durability.sealingKey references binding namespace '" + ns
          + "', but no matching entry exists in 'binds' or 'capability.binds'.",
        path,
      }];
    }
    if (!keys.has(key)) {
      return [{
        message: "durability.sealingKey references key '" + key + "' on binding '" + ns
          + "', but that key is not declared. Add it to binds[" + ns + "].keys.",
        path,
      }];
    }
    return [];
  }
  if (!bindings.allKeys.has(key)) {
    return [{
      message: "durability.sealingKey references key '" + key + "', but no binding declares "
        + "it. Add it to the keys of an entry in 'binds'.",
      path,
    }];
  }
  return [];
}

function collectBindings(doc) {
  const result = { byNamespace: new Map(), allKeys: new Set() };
  const all = [];
  if (Array.isArray(doc.binds)) {
    all.push(...doc.binds);
  }
  if (doc.capability && Array.isArray(doc.capability.binds)) {
    all.push(...doc.capability.binds);
  }
  for (const bind of all) {
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
  return result;
}

function hasElicit(steps) {
  return entries(steps).some((s) => s.value && s.value.type === "elicit");
}

// Flows that ask for input, directly or through the flows they call.
function elicitingFlows(capability) {
  const flows = new Map();
  for (const { key, flow } of flowsOf(capability)) {
    flows.set(key, flow);
  }
  const memo = new Map();
  const asks = (key, visiting) => {
    if (memo.has(key)) {
      return memo.get(key);
    }
    const flow = flows.get(key);
    if (!flow || visiting.has(key)) {
      return false;
    }
    visiting.add(key);
    let result = hasElicit(flow.steps);
    for (const s of entries(flow.steps)) {
      const step = s.value;
      if (!result && step && step.type === "call" && typeof step.call === "string") {
        result = asks(step.call, visiting);
      }
    }
    visiting.delete(key);
    memo.set(key, result);
    return result;
  };
  return (key) => typeof key === "string" && asks(key, new Set());
}

function checkElicitOverRest(capability) {
  const asks = elicitingFlows(capability);
  const results = [];
  for (const e of entries(capability.exposes)) {
    const adapter = e.value;
    if (!adapter || adapter.type !== "rest") {
      continue;
    }
    const base = ["capability", "exposes", ...e.locator];
    for (const r of entries(adapter.resources)) {
      for (const o of entries(r.value && r.value.operations)) {
        const op = o.value;
        if (!op || typeof op !== "object") {
          continue;
        }
        const opPath = base.concat("resources", ...r.locator, "operations", ...o.locator);
        let target = null;
        if (hasElicit(op.steps)
            || entries(op.steps).some((s) => s.value && s.value.type === "call"
              && asks(s.value.call))) {
          target = opPath.concat("steps");
        } else if (asks(op.ref)) {
          target = opPath.concat("ref");
        } else if (asks(op.call)) {
          target = opPath.concat("call");
        }
        if (target) {
          results.push({
            message: "Operation '" + o.key + "' reaches an elicit step, which REST cannot "
              + "serve yet: a call that reaches it answers 501 Not Implemented. Expose the "
              + "same steps as an MCP tool to ask the caller.",
            path: target,
          });
        }
      }
    }
  }
  return results;
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

function findControl(capability) {
  for (const e of entries(capability.exposes)) {
    const adapter = e.value;
    if (adapter && adapter.type === "control") {
      const declared = adapter.durability && typeof adapter.durability.mode === "string"
        ? adapter.durability.mode : "none";
      return { mode: declared, adapter, path: ["capability", "exposes", ...e.locator] };
    }
  }
  return { mode: "none", adapter: null, path: ["capability", "exposes"] };
}

function flowsOf(capability) {
  const flows = []; // { key, flow, path }
  for (const agg of entries(capability.aggregates)) {
    const aggregate = agg.value;
    if (!aggregate || typeof aggregate.namespace !== "string") {
      continue;
    }
    for (const f of entries(aggregate.flows)) {
      if (f.value && typeof f.value === "object") {
        flows.push({
          key: aggregate.namespace + "." + f.key,
          flow: f.value,
          path: ["capability", "aggregates", ...agg.locator, "flows", ...f.locator],
        });
      }
    }
  }
  return flows;
}

function checkTaskMode(capability) {
  const results = [];
  for (const { key, flow, path } of flowsOf(capability)) {
    const interaction = flow.semantics && flow.semantics.interaction;
    if (interaction && interaction.mode === "task") {
      results.push({
        message: "Flow '" + key + "' declares interaction mode 'task', but the capability "
          + "has no durability mode to keep tasks in. Declare `durability: { mode: memory }` "
          + "on the control port.",
        path: path.concat("semantics", "interaction", "mode"),
      });
    }
  }
  return results;
}

function buildSafety(capability) {
  const safe = new Map(); // "ns.name" -> true | false
  const imported = new Set();
  for (const c of entries(capability.consumes)) {
    const consumed = c.value;
    if (consumed && typeof consumed.import === "string") {
      imported.add(typeof consumed.as === "string" ? consumed.as : consumed.import);
      continue;
    }
    if (!consumed || typeof consumed.namespace !== "string") {
      continue;
    }
    if (consumed.type === "mcp") {
      for (const t of entries(consumed.tools)) {
        const hints = t.value && t.value.hints;
        safe.set(consumed.namespace + "." + t.key, !!(hints && hints.readOnly === true));
      }
    } else {
      for (const r of entries(consumed.resources)) {
        for (const o of entries(r.value && r.value.operations)) {
          const method = o.value && typeof o.value.method === "string"
            ? o.value.method.toUpperCase() : "";
          safe.set(consumed.namespace + "." + o.key, SAFE_METHODS.has(method));
        }
      }
    }
  }
  for (const { key, flow } of flowsOf(capability)) {
    safe.set(key, !!(flow.semantics && flow.semantics.safe === true));
  }
  return { safe, imported };
}

function stepContainers(capability) {
  const containers = flowsOf(capability).map(({ key, flow, path }) => ({
    label: "Flow '" + key + "'", steps: flow.steps, path: path.concat("steps"),
  }));
  for (const e of entries(capability.exposes)) {
    const adapter = e.value;
    const base = ["capability", "exposes", ...e.locator];
    if (!adapter) {
      continue;
    }
    if (adapter.type === "mcp") {
      for (const t of entries(adapter.tools)) {
        containers.push({ label: "Tool '" + t.key + "'", steps: t.value && t.value.steps,
          path: base.concat("tools", ...t.locator, "steps") });
      }
      for (const r of entries(adapter.resources)) {
        containers.push({ label: "Resource '" + r.key + "'", steps: r.value && r.value.steps,
          path: base.concat("resources", ...r.locator, "steps") });
      }
    } else if (adapter.type === "rest") {
      for (const r of entries(adapter.resources)) {
        for (const o of entries(r.value && r.value.operations)) {
          containers.push({ label: "Operation '" + o.key + "'", steps: o.value && o.value.steps,
            path: base.concat("resources", ...r.locator, "operations", ...o.locator, "steps") });
        }
      }
    }
  }
  return containers;
}

function checkElicitSafety(capability) {
  const { safe, imported } = buildSafety(capability);
  const results = [];
  for (const container of stepContainers(capability)) {
    let unsafeCall = null;
    for (const s of entries(container.steps)) {
      const step = s.value;
      if (!step || typeof step !== "object") {
        continue;
      }
      if (step.type === "call" && typeof step.call === "string" && !unsafeCall) {
        const dot = step.call.indexOf(".");
        const namespace = dot > 0 ? step.call.substring(0, dot) : step.call;
        if (!imported.has(namespace) && safe.get(step.call) === false) {
          unsafeCall = s.key;
        }
      }
      if (step.type === "elicit" && unsafeCall) {
        results.push({
          message: container.label + " asks for input in step '" + s.key + "' after step '"
            + unsafeCall + "', which is not read-only. Without a journal the answer replays "
            + "the steps, so that call would run again. Declare a durability mode on the "
            + "control port, or move the question before it.",
          path: container.path.concat(...s.locator),
        });
      }
    }
  }
  return results;
}
