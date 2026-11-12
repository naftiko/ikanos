/**
 * Spectral custom function: definitions
 *
 * Checks the shared `definitions` of MCP adapters, REST adapters and
 * aggregates (#910, #1000) and their uses: `inputParameterSets: [names]` on a
 * tool, a REST resource or operation, or a flow, and `schema: name` on inputs,
 * inside schemas and in outputs. Definitions are local to their adapter or
 * aggregate. Select the check with `functionOptions.check`:
 *
 *   - "references": a set or schema name that the namespace does not declare,
 *                   or that names the other kind of definition;
 *   - "conflicts":  two sets of one unit (tool, resource, operation or flow)
 *                   declare the same input;
 *   - "cycles":     schemas reference each other in a cycle;
 *   - "unused":     a set or schema that no unit of the namespace uses,
 *                   directly or through another definition;
 *   - "outputKeywords": a schema used in an orchestrated output declares
 *                   keywords that an output does not carry (informational).
 *
 * Covers MCP adapters, REST adapters and aggregates in a capability and at the
 * root of a standalone file. The engine and `ikanos validate` run the same checks when
 * they expand the definitions; the JSON Schema already rejects qualified and
 * `$ref` references and keys beside `schema:`.
 */
export default function definitions(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = options && options.check;
  const results = [];
  for (const owner of owners(targetVal)) {
    const model = buildModel(owner);
    if (check === "references") {
      checkReferences(model, results);
    } else if (check === "conflicts") {
      checkConflicts(model, results);
    } else if (check === "cycles") {
      checkCycles(model, results);
    } else if (check === "unused") {
      checkUnused(model, results);
    } else if (check === "outputKeywords") {
      checkOutputKeywords(model, results);
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

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** The units of a map or list, with their paths in the document. */
function unitsAt(collection, path) {
  return entries(collection).map((e) => ({
    key: e.key,
    value: e.value,
    path: path.concat(...e.locator),
  }));
}

/** The resources of a REST adapter and their operations, as units. */
function restUnits(adapter, path) {
  const units = [];
  for (const r of unitsAt(adapter.resources, path.concat("resources"))) {
    units.push(r);
    if (isObject(r.value)) {
      units.push(...unitsAt(r.value.operations, r.path.concat("operations")));
    }
  }
  return units;
}

/** MCP adapters, REST adapters and aggregates of the document, with their units. */
function owners(doc) {
  const result = [];
  const roots = [];
  if (isObject(doc.capability)) {
    roots.push({ section: doc.capability, path: ["capability"] });
  }
  roots.push({ section: doc, path: [] });
  for (const root of roots) {
    for (const e of entries(root.section.exposes)) {
      const adapter = e.value;
      const adapterPath = root.path.concat("exposes", ...e.locator);
      if (isObject(adapter) && adapter.type === "mcp") {
        result.push({
          label: "MCP adapter '" + adapter.namespace + "'",
          unitKind: "tool",
          node: adapter,
          path: adapterPath,
          units: unitsAt(adapter.tools, adapterPath.concat("tools")),
        });
      } else if (isObject(adapter) && adapter.type === "rest") {
        result.push({
          label: "REST adapter '" + adapter.namespace + "'",
          unitKind: "resource or operation",
          node: adapter,
          path: adapterPath,
          units: restUnits(adapter, adapterPath),
        });
      }
    }
    for (const a of entries(root.section.aggregates)) {
      const aggregate = a.value;
      if (isObject(aggregate) && !aggregate.from) {
        const aggregatePath = root.path.concat("aggregates", ...a.locator);
        result.push({
          label: "aggregate '" + aggregate.namespace + "'",
          unitKind: "flow",
          node: aggregate,
          path: aggregatePath,
          units: unitsAt(aggregate.flows, aggregatePath.concat("flows")),
        });
      }
    }
  }
  return result;
}

/** Collect the `schema:` references of an input declaration. */
function walkInput(node, path, refs) {
  if (!isObject(node)) {
    return;
  }
  if (typeof node.schema === "string") {
    refs.push({ kind: "schema", name: node.schema, path: path.concat("schema") });
  }
  walkInput(node.items, path.concat("items"), refs);
  walkInput(node.values, path.concat("values"), refs);
  for (const p of entries(isObject(node.properties) ? node.properties : null)) {
    walkInput(p.value, path.concat("properties", ...p.locator), refs);
  }
}

/** Collect the `schema:` references of an output declaration. */
function walkOutput(node, path, refs) {
  if (!isObject(node)) {
    return;
  }
  if (typeof node.schema === "string") {
    refs.push({ kind: "schema", name: node.schema, path: path.concat("schema"), output: true });
  }
  const items = node.items;
  if (isObject(items) && typeof items.type !== "string" && typeof items.schema !== "string") {
    // Orchestrated items: a map of named property declarations.
    for (const p of entries(items)) {
      walkOutput(p.value, path.concat("items", ...p.locator), refs);
    }
  } else {
    walkOutput(items, path.concat("items"), refs);
  }
  for (const p of entries(isObject(node.properties) ? node.properties : null)) {
    walkOutput(p.value, path.concat("properties", ...p.locator), refs);
  }
}

/**
 * Copy the own entries of a definitions map into a null-prototype object, so
 * that `name in dict` and `dict[name]` only see declared definitions, never
 * inherited members such as `constructor` or `toString`.
 */
function dictionary(value) {
  const dict = Object.create(null);
  if (isObject(value)) {
    for (const key of Object.keys(value)) {
      dict[key] = value[key];
    }
  }
  return dict;
}

function buildModel(owner) {
  const defs = isObject(owner.node.definitions) ? owner.node.definitions : {};
  const sets = dictionary(defs.inputParameterSets);
  const schemas = dictionary(defs.schemas);
  const defsPath = owner.path.concat("definitions");

  // References made by each set and each schema.
  const setRefs = new Map();
  for (const s of entries(sets)) {
    const refs = [];
    for (const p of entries(s.value)) {
      walkInput(p.value, defsPath.concat("inputParameterSets", s.key, p.key), refs);
    }
    setRefs.set(s.key, refs);
  }
  const schemaRefs = new Map();
  for (const s of entries(schemas)) {
    const refs = [];
    walkInput(s.value, defsPath.concat("schemas", s.key), refs);
    schemaRefs.set(s.key, refs);
  }

  // References made by each tool or flow.
  const units = [];
  for (const u of owner.units) {
    const unit = u.value;
    if (!isObject(unit)) {
      continue;
    }
    const unitPath = u.path;
    const refs = [];
    if (Array.isArray(unit.inputParameterSets)) {
      unit.inputParameterSets.forEach((name, index) => {
        refs.push({ kind: "set", name, path: unitPath.concat("inputParameterSets", index) });
      });
    }
    for (const p of entries(unit.inputParameters)) {
      walkInput(p.value, unitPath.concat("inputParameters", ...p.locator), refs);
    }
    if (isObject(unit.outputParameters)) {
      for (const p of entries(unit.outputParameters)) {
        walkOutput(p.value, unitPath.concat("outputParameters", p.key), refs);
      }
    } else {
      for (const p of entries(unit.outputParameters)) {
        walkOutput(p.value, unitPath.concat("outputParameters", ...p.locator), refs);
      }
    }
    const steps = unit.steps;
    const orchestrated = Array.isArray(steps) ? steps.length > 0
      : isObject(steps) && Object.keys(steps).length > 0;
    units.push({ name: u.key, unit, path: unitPath, refs, orchestrated });
  }

  return { owner, sets, schemas, defsPath, setRefs, schemaRefs, units };
}

function allReferences(model) {
  const refs = [];
  for (const unit of model.units) {
    refs.push(...unit.refs);
  }
  for (const r of model.setRefs.values()) {
    refs.push(...r);
  }
  for (const r of model.schemaRefs.values()) {
    refs.push(...r);
  }
  return refs;
}

function declared(names) {
  const list = Object.keys(names);
  return list.length === 0 ? "none" : list.join(", ");
}

function checkReferences(model, results) {
  const label = model.owner.label;
  for (const ref of allReferences(model)) {
    if (typeof ref.name !== "string") {
      continue;
    }
    if (ref.kind === "set" && !(ref.name in model.sets)) {
      results.push({
        message: ref.name in model.schemas
          ? "'" + ref.name + "' is a schema, not an input parameter set. Use it with 'schema: "
            + ref.name + "' on an input."
          : "Unknown input parameter set '" + ref.name + "'. " + label + " declares "
            + declared(model.sets) + ".",
        path: ref.path,
      });
    } else if (ref.kind === "schema" && !(ref.name in model.schemas)) {
      results.push({
        message: ref.name in model.sets
          ? "'" + ref.name + "' is an input parameter set, not a schema. Use it with"
            + " 'inputParameterSets: [" + ref.name + "]' on the " + model.owner.unitKind + "."
          : "Unknown schema '" + ref.name + "'. " + label + " declares "
            + declared(model.schemas) + ".",
        path: ref.path,
      });
    }
  }
}

function checkConflicts(model, results) {
  for (const unit of model.units) {
    const origins = new Map();
    for (const ref of unit.refs) {
      if (ref.kind !== "set" || !isObject(model.sets[ref.name])) {
        continue;
      }
      for (const input of Object.keys(model.sets[ref.name])) {
        const previous = origins.get(input);
        if (previous !== undefined) {
          results.push({
            message: "Input '" + input + "' comes from both input parameter sets '" + previous
              + "' and '" + ref.name + "'. Keep it in one set, or declare it on the "
              + model.owner.unitKind + ".",
            path: ref.path,
          });
        } else {
          origins.set(input, ref.name);
        }
      }
    }
  }
}

function checkCycles(model, results) {
  // Iterative depth-first walk: a long chain of schemas must not overflow the
  // call stack.
  const state = new Map(); // name -> 1 visiting, 2 done
  const referenced = (name) => (model.schemaRefs.get(name) || [])
    .map((ref) => ref.name)
    .filter((ref) => typeof ref === "string" && ref in model.schemas);
  for (const root of Object.keys(model.schemas)) {
    if (state.has(root)) {
      continue;
    }
    const path = [root];
    const pending = [referenced(root)[Symbol.iterator]()];
    state.set(root, 1);
    while (pending.length > 0) {
      const step = pending[pending.length - 1].next();
      if (step.done) {
        state.set(path.pop(), 2);
        pending.pop();
        continue;
      }
      const name = step.value;
      if (!state.has(name)) {
        state.set(name, 1);
        path.push(name);
        pending.push(referenced(name)[Symbol.iterator]());
      } else if (state.get(name) === 1) {
        const cycle = path.slice(path.indexOf(name)).concat(name);
        results.push({
          message: "Schemas reference each other in a cycle: " + cycle.join(" -> ") + ".",
          path: model.defsPath.concat("schemas", cycle[0]),
        });
      }
    }
  }
}

function checkUnused(model, results) {
  const usedSets = new Set();
  const usedSchemas = new Set();
  const pending = [];
  const use = (ref) => {
    if (ref.kind === "set" && ref.name in model.sets && !usedSets.has(ref.name)) {
      usedSets.add(ref.name);
      pending.push(...(model.setRefs.get(ref.name) || []));
    } else if (ref.kind === "schema" && ref.name in model.schemas
        && !usedSchemas.has(ref.name)) {
      usedSchemas.add(ref.name);
      pending.push(...(model.schemaRefs.get(ref.name) || []));
    }
  };
  for (const unit of model.units) {
    unit.refs.forEach(use);
  }
  while (pending.length > 0) {
    use(pending.pop());
  }
  const label = model.owner.label;
  const unitKind = model.owner.unitKind;
  for (const name of Object.keys(model.sets)) {
    if (!usedSets.has(name)) {
      results.push({
        message: label + " declares the input parameter set '" + name + "', which no "
          + unitKind + " uses.",
        path: model.defsPath.concat("inputParameterSets", name),
      });
    }
  }
  for (const name of Object.keys(model.schemas)) {
    if (!usedSchemas.has(name)) {
      results.push({
        message: label + " declares the schema '" + name + "', which no " + unitKind
          + " uses.",
        path: model.defsPath.concat("schemas", name),
      });
    }
  }
}

/** Keywords of a schema that an orchestrated output does not declare. */
const INPUT_ONLY_KEYWORDS = ["description", "enum", "format", "minimum", "maximum", "required",
  "additionalProperties"];

/**
 * Whether a schema declares a keyword. `false` is absent, except for
 * `additionalProperties`, where it closes the object: a constraint the output
 * drops.
 */
function declares(node, keyword) {
  const value = node[keyword];
  return value !== undefined && value !== null
    && (value !== false || keyword === "additionalProperties")
    && !(Array.isArray(value) && value.length === 0);
}

/**
 * The input-only keywords a schema declares, on itself and on what an
 * orchestrated output keeps of it: the properties of an object, and the items
 * of an array when they are objects with properties. Iterative, and each
 * schema is visited once, so cycles and long chains end.
 */
function droppedKeywords(model, name) {
  const found = new Set();
  const visited = new Set([name]);
  const target = (node) => {
    let current = node;
    const seen = new Set();
    while (isObject(current) && typeof current.schema === "string"
        && current.schema in model.schemas && !seen.has(current.schema)) {
      seen.add(current.schema);
      current = model.schemas[current.schema];
    }
    return current;
  };
  const pending = [model.schemas[name]];
  while (pending.length > 0) {
    const node = pending.pop();
    if (!isObject(node)) {
      continue;
    }
    for (const keyword of INPUT_ONLY_KEYWORDS) {
      if (declares(node, keyword)) {
        found.add(keyword);
      }
    }
    if (typeof node.schema === "string") {
      if (node.schema in model.schemas && !visited.has(node.schema)) {
        visited.add(node.schema);
        pending.push(model.schemas[node.schema]);
      }
      continue;
    }
    if (node.type === "object" && isObject(node.properties)) {
      pending.push(...Object.values(node.properties));
    } else if (node.type === "array") {
      const items = target(node.items);
      if (isObject(items) && items.type === "object" && isObject(items.properties)
          && Object.keys(items.properties).length > 0) {
        pending.push(node.items);
      }
    }
  }
  return INPUT_ONLY_KEYWORDS.filter((keyword) => found.has(keyword));
}

function checkOutputKeywords(model, results) {
  for (const unit of model.units) {
    if (!unit.orchestrated) {
      continue;
    }
    for (const ref of unit.refs) {
      if (!ref.output || typeof ref.name !== "string" || !(ref.name in model.schemas)) {
        continue;
      }
      const dropped = droppedKeywords(model, ref.name);
      if (dropped.length > 0) {
        results.push({
          message: "Schema '" + ref.name + "' is used in an orchestrated output, which does"
            + " not carry its keywords " + dropped.join(", ") + ". An output keeps only the"
            + " schema's type, properties, and items that are objects with properties.",
          path: ref.path,
        });
      }
    }
  }
}
