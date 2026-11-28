/**
 * Polychro rule function: mcp-files
 *
 * Checks the file inputs and file transfer targets of the experimental MCP files
 * extension (`io.modelcontextprotocol/files`). Select the check with
 * `functionOptions.check`:
 *
 *   - "max-size":     a `type: file` input declares no `maxSize`;
 *   - "accept":       a `type: file` input declares no `accept`.
 *                     Both cover the inputs of aggregate flows and of exposed MCP
 *                     tools, and the input parameter sets of their `definitions`;
 *   - "target-https": a consumed HTTP operation that a flow's `upload.presign` or
 *                     `upload.verify` calls, or that an MCP tool with `download`
 *                     calls (its `call`, its `call` steps, or those of the flow it
 *                     `ref`s), has a `baseUri` that is neither `https` nor `http` on
 *                     a loopback host. Its answer carries the presigned URL or the
 *                     download URL the client is sent to. A `baseUri` taken whole
 *                     from a binding, and a namespace imported from another
 *                     document, are out of reach of a single-document lint and skipped;
 *   - "omit-allowlist": an MCP adapter with `files` returns `credentials: omit`
 *                     targets (a tool that `ref`s a flow with `upload`, or a tool with
 *                     `download`) but declares no `files.allowedOrigins`;
 *   - "single-use":   a flow with `upload` whose `semantics` say neither `safe` nor
 *                     `idempotent`, and a tool that takes hosted uploads
 *                     (`files.uploads.hosted: true`), get a single-use `requestState`.
 *
 * Walks both `capability.*` and root-level sections (shared section documents).
 * Keyed maps and arrays are both accepted. Messages never quote a Mustache
 * expression: the linter could read double braces as a placeholder.
 */
const HTTPS_OR_LOOPBACK =
  /^(https:\/\/|http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:[0-9]+)?(\/|$)|\{\{[A-Za-z0-9_.-]+\}\}$)/i;

export default function mcpFiles(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const model = buildModel(targetVal);
  const check = options && options.check;
  if (check === "max-size") {
    return checkFileInputs(model, "maxSize");
  }
  if (check === "accept") {
    return checkFileInputs(model, "accept");
  }
  if (check === "target-https") {
    return checkTargetHttps(model);
  }
  if (check === "omit-allowlist") {
    return checkOmitAllowlist(model);
  }
  if (check === "single-use") {
    return checkSingleUse(model);
  }
  return;
}

function buildModel(doc) {
  const capability = doc.capability && typeof doc.capability === "object" ? doc.capability : {};
  const sections = (name) => [
    { list: doc[name], base: [name] },
    { list: capability[name], base: ["capability", name] },
  ];

  const consumes = new Map(); // namespace -> consumed adapter (imports excluded)
  for (const { list } of sections("consumes")) {
    for (const [, consumed] of entries(list)) {
      if (consumed && typeof consumed.namespace === "string"
        && typeof consumed.import !== "string") {
        consumes.set(consumed.namespace, consumed);
      }
    }
  }

  const flows = new Map(); // "ns.flow" -> { flow, path, sets, setsPath }
  for (const { list, base } of sections("aggregates")) {
    for (const [aggKey, aggregate] of entries(list)) {
      if (!aggregate || typeof aggregate.namespace !== "string") {
        continue;
      }
      const aggPath = [...base, aggKey];
      for (const [flowKey, flow] of entries(aggregate.flows)) {
        if (flow && typeof flow === "object") {
          flows.set(aggregate.namespace + "." + flowKey, {
            name: aggregate.namespace + "." + flowKey,
            flow,
            path: [...aggPath, "flows", flowKey],
            owner: aggregate,
            ownerPath: aggPath,
          });
        }
      }
    }
  }

  const adapters = []; // MCP exposes adapters, with their path
  for (const { list, base } of sections("exposes")) {
    for (const [key, adapter] of entries(list)) {
      if (adapter && adapter.type === "mcp") {
        adapters.push({ adapter, path: [...base, key] });
      }
    }
  }

  const aggregates = [];
  for (const { list, base } of sections("aggregates")) {
    for (const [key, aggregate] of entries(list)) {
      if (aggregate && typeof aggregate === "object") {
        aggregates.push({ owner: aggregate, path: [...base, key] });
      }
    }
  }
  return { consumes, flows, adapters, aggregates };
}

// ── file inputs ──────────────────────────────────────────────────────

function checkFileInputs(model, field) {
  const results = [];
  const report = (key, input, path, where) => {
    if (!isFile(input) || present(input[field])) {
      return;
    }
    const name = typeof input.name === "string" ? input.name : String(key);
    results.push({
      message: field === "maxSize"
        ? "File input '" + name + "' of " + where + " declares no maxSize. Bound it (such as"
          + " 50MiB): without it, a file is bounded only by the adapter's inline or binary"
          + " limits, and an out-of-band upload not at all."
        : "File input '" + name + "' of " + where + " declares no accept. List the media"
          + " types or extensions it takes: files are untrusted, and accept is what refuses"
          + " the others before the flow runs.",
      path: [...path, key],
    });
  };
  for (const { owner, path } of model.aggregates) {
    for (const [flowKey, flow] of entries(owner.flows)) {
      for (const [name, input] of entries(flow && flow.inputParameters)) {
        report(name, input, [...path, "flows", flowKey, "inputParameters"],
          "flow '" + owner.namespace + "." + flowKey + "'");
      }
    }
    reportSets(owner, path, "aggregate '" + owner.namespace + "'", report);
  }
  for (const { adapter, path } of model.adapters) {
    for (const [toolKey, tool] of entries(adapter.tools)) {
      for (const [name, input] of entries(tool && tool.inputParameters)) {
        report(name, input, [...path, "tools", toolKey, "inputParameters"],
          "MCP tool '" + toolKey + "'");
      }
    }
    reportSets(adapter, path, "MCP adapter '" + adapter.namespace + "'", report);
  }
  return results;
}

function reportSets(owner, path, where, report) {
  const sets = owner.definitions && owner.definitions.inputParameterSets;
  for (const [setKey, set] of entries(sets)) {
    for (const [name, input] of entries(set)) {
      report(name, input, [...path, "definitions", "inputParameterSets", setKey],
        "input parameter set '" + setKey + "' of " + where);
    }
  }
}

function isFile(input) {
  return input && typeof input === "object" && input.type === "file";
}

function present(value) {
  return value !== undefined && value !== null && !(Array.isArray(value) && value.length === 0);
}

/** Whether a unit (flow or tool) takes a file: its own inputs or its input parameter sets. */
function takesFile(unit, owner) {
  if (entries(unit && unit.inputParameters).some(([, input]) => isFile(input))) {
    return true;
  }
  const sets = owner && owner.definitions && owner.definitions.inputParameterSets;
  return asArray(unit && unit.inputParameterSets).some((setName) =>
    typeof setName === "string" && sets && typeof sets === "object"
      && entries(sets[setName]).some(([, input]) => isFile(input)));
}

// ── transfer targets ─────────────────────────────────────────────────

function checkTargetHttps(model) {
  const results = [];
  const checkCall = (call, path, role) => {
    const target = split(call);
    const consumed = target && model.consumes.get(target.namespace);
    if (!consumed || consumed.type !== "http" || typeof consumed.baseUri !== "string"
      || HTTPS_OR_LOOPBACK.test(consumed.baseUri.trim())) {
      return;
    }
    results.push({
      message: role + " calls '" + call + "', but consumed adapter '" + target.namespace
        + "' has a baseUri that is not https. Its answer carries the URL the client sends"
        + " the file to, or gets it from, with no credential: fetch it over https.",
      path,
    });
  };
  for (const { name, flow, path } of model.flows.values()) {
    const upload = flow.upload;
    if (!upload || typeof upload !== "object") {
      continue;
    }
    for (const step of ["presign", "verify"]) {
      if (upload[step] && typeof upload[step].call === "string") {
        checkCall(upload[step].call, [...path, "upload", step, "call"],
          "upload." + step + " of flow '" + name + "'");
      }
    }
  }
  for (const { adapter, path } of model.adapters) {
    for (const [toolKey, tool] of entries(adapter.tools)) {
      if (!tool || !tool.download) {
        continue;
      }
      const role = "MCP tool '" + toolKey + "', which declares download,";
      const toolPath = [...path, "tools", toolKey];
      for (const { call, callPath } of callsOf(tool, toolPath)) {
        checkCall(call, callPath, role);
      }
      const flow = typeof tool.ref === "string" ? model.flows.get(tool.ref) : null;
      if (flow) {
        for (const { call, callPath } of callsOf(flow.flow, flow.path)) {
          checkCall(call, callPath, role + " through flow '" + flow.name + "',");
        }
      }
    }
  }
  return results;
}

/** The `call` of a unit and of its `call` steps, with their paths. */
function callsOf(unit, path) {
  const calls = [];
  if (typeof unit.call === "string") {
    calls.push({ call: unit.call, callPath: [...path, "call"] });
  }
  for (const [stepKey, step] of entries(unit.steps)) {
    if (step && step.type === "call" && typeof step.call === "string") {
      calls.push({ call: step.call, callPath: [...path, "steps", stepKey, "call"] });
    }
  }
  return calls;
}

function checkOmitAllowlist(model) {
  const results = [];
  for (const { adapter, path } of model.adapters) {
    const files = adapter.files;
    if (!files || typeof files !== "object" || present(files.allowedOrigins)) {
      continue;
    }
    const tools = [];
    for (const [toolKey, tool] of entries(adapter.tools)) {
      if (!tool) {
        continue;
      }
      const flow = typeof tool.ref === "string" ? model.flows.get(tool.ref) : null;
      if (tool.download || (flow && flow.flow.upload)) {
        tools.push("'" + toolKey + "'");
      }
    }
    if (tools.length > 0) {
      results.push({
        message: "MCP adapter '" + adapter.namespace + "' returns credentials: omit targets"
          + " (presigned uploads or download URLs, from tool" + (tools.length > 1 ? "s " : " ")
          + tools.join(", ") + ") but declares no files.allowedOrigins. The client's host"
          + " approves the call before it sees the target: list the origins your storage"
          + " uses, and the adapter refuses any other.",
        path: [...path, "files"],
      });
    }
  }
  return results;
}

// ── single-use requestState ──────────────────────────────────────────

function checkSingleUse(model) {
  const results = [];
  for (const { name, flow, path, owner } of model.flows.values()) {
    if (!flow.upload || typeof flow.upload !== "object" || !takesFile(flow, owner)) {
      continue;
    }
    const semantics = flow.semantics && typeof flow.semantics === "object" ? flow.semantics : {};
    if (semantics.safe === true || semantics.idempotent === true) {
      continue;
    }
    results.push({
      message: "Flow '" + name + "' takes presigned uploads and is not idempotent: over MCP,"
        + " the requestState of its upload_required result runs it once only, and a second"
        + " retry with it is refused (-32602), so a client that lost the answer must call the"
        + " tool again and upload again. Declare semantics.idempotent: true if running it"
        + " twice is harmless.",
      path: [...path, "upload"],
    });
  }
  for (const { adapter, path } of model.adapters) {
    const uploads = adapter.files && adapter.files.uploads;
    if (!uploads || uploads.hosted !== true) {
      continue;
    }
    for (const [toolKey, tool] of entries(adapter.tools)) {
      if (!tool) {
        continue;
      }
      const flow = typeof tool.ref === "string" ? model.flows.get(tool.ref) : null;
      if (flow && flow.flow.upload) {
        continue; // presigned, reported on the flow
      }
      if (!takesFile(tool, adapter) && !(flow && takesFile(flow.flow, flow.owner))) {
        continue;
      }
      results.push({
        message: "MCP tool '" + toolKey + "' takes hosted uploads: the requestState of its"
          + " upload_required result runs it once only, whatever its semantics, since the"
          + " bytes are deleted once the call completes: a second retry with it is refused"
          + " (-32602), and the client must call the tool again and upload again.",
        path: [...path, "tools", toolKey],
      });
    }
  }
  return results;
}

// ── helpers ──────────────────────────────────────────────────────────

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
