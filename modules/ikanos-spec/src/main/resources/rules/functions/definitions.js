/**
 * Spectral custom function: definitions
 *
 * Checks the shared `definitions` of MCP adapters and aggregates (#910) and
 * their uses: `inputParameterSets: [names]` on a tool or flow, and
 * `schema: name` on inputs, inside schemas and in outputs. Definitions are
 * local to their adapter or aggregate. Select the check with
 * `functionOptions.check`:
 *
 *   - "references": a set or schema name that the namespace does not declare,
 *                   or that names the other kind of definition;
 *   - "conflicts":  two sets of one tool or flow declare the same input;
 *   - "cycles":     schemas reference each other in a cycle;
 *   - "unused":     a set or schema that no tool or flow of the namespace
 *                   uses, directly or through another definition.
 *
 * Covers MCP adapters and aggregates in a capability and at the root of a
 * standalone file. The engine and `ikanos validate` run the same checks when
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

/** MCP adapters and aggregates of the document, with their units. */
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
      if (isObject(adapter) && adapter.type === "mcp") {
        result.push({
          label: "MCP adapter '" + adapter.namespace + "'",
          unitKind: "tool",
          node: adapter,
          path: root.path.concat("exposes", ...e.locator),
          units: entries(adapter.tools),
          unitsKey: "tools",
        });
      }
    }
    for (const a of entries(root.section.aggregates)) {
      const aggregate = a.value;
      if (isObject(aggregate) && !aggregate.from) {
        result.push({
          label: "aggregate '" + aggregate.namespace + "'",
          unitKind: "flow",
          node: aggregate,
          path: root.path.concat("aggregates", ...a.locator),
          units: entries(aggregate.flows),
          unitsKey: "flows",
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
    refs.push({ kind: "schema", name: node.schema, path: path.concat("schema") });
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

function buildModel(owner) {
  const defs = isObject(owner.node.definitions) ? owner.node.definitions : {};
  const sets = isObject(defs.inputParameterSets) ? defs.inputParameterSets : {};
  const schemas = isObject(defs.schemas) ? defs.schemas : {};
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
    const unitPath = owner.path.concat(owner.unitsKey, ...u.locator);
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
    units.push({ name: u.key, unit, path: unitPath, refs });
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
  const state = new Map(); // name -> 1 visiting, 2 done
  const visit = (name, stack) => {
    if (state.get(name) === 2) {
      return;
    }
    if (state.get(name) === 1) {
      const cycle = stack.slice(stack.indexOf(name)).concat(name);
      results.push({
        message: "Schemas reference each other in a cycle: " + cycle.join(" -> ") + ".",
        path: model.defsPath.concat("schemas", cycle[0]),
      });
      return;
    }
    state.set(name, 1);
    stack.push(name);
    for (const ref of model.schemaRefs.get(name) || []) {
      if (ref.name in model.schemas) {
        visit(ref.name, stack);
      }
    }
    stack.pop();
    state.set(name, 2);
  };
  for (const name of Object.keys(model.schemas)) {
    visit(name, []);
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
