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
 *   - "mode-available": the mode is `local` or `distributed`, whose store modules
 *                       are not available yet: the engine rejects the capability.
 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

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
    if (control.mode === "local" || control.mode === "distributed") {
      return [{
        message: "Durability mode '" + control.mode + "' is not available yet: its store "
          + "module ships with a later release. Use 'memory' or 'none'.",
        path: control.path.concat("durability", "mode"),
      }];
    }
    return [];
  }
  return;
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
      return { mode: declared, path: ["capability", "exposes", ...e.locator] };
    }
  }
  return { mode: "none", path: ["capability", "exposes"] };
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
