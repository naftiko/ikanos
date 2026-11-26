/**
 * Polychro rule function: definitions
 *
 * Checks the shared `definitions` of MCP adapters, REST adapters and
 * aggregates (#910, #1000) and their uses: `inputParameterSets: [names]` on a
 * tool, a REST resource or operation, or a flow, and `schema: name` on inputs,
 * inside schemas and in outputs. Definitions are local to their adapter or
 * aggregate. Select the check with `functionOptions.check`:
 *
 *   - "references": a set, schema or mapping set name that the namespace does
 *                   not declare, or that names another kind of definition;
 *   - "conflicts":  two sets of one unit (tool, resource, operation or flow)
 *                   declare the same input;
 *   - "cycles":     schemas reference each other in a cycle;
 *   - "unused":     a set, schema or mapping set that no unit of the
 *                   namespace uses, directly or through another definition;
 *   - "outputKeywords": a schema used in an orchestrated or mapped output
 *                   declares keywords that an output does not carry
 *                   (informational);
 *   - "mappings":   a schema used in a mapped (call-mode) output is not bound
 *                   by its `mappings` and `mappingSets` (#1036, #1133),
 *                   merged by path: a scalar, array or object
 *                   without properties left unbound, a path the schema does
 *                   not have, a binding the runtime never reads (object
 *                   items, below nested arrays, a `mapping` on an output
 *                   object), a value that is not a JSONPath, a property name
 *                   a path cannot address, a key beside `schema:` other than
 *                   `mappings`, `mappingSets` and `mapping`, `mappings` or
 *                   `mappingSets` without `schema:`, `schema:` / `mappings` /
 *                   `mappingSets` in a mock output or an MCP resource output,
 *                   or a mapping set value that is not a JSONPath. In steps
 *                   mode (#1150), a `mappings` entry listing `mappingSets`
 *                   without the roots of their paths, a set path through
 *                   array items, or a path that the output the entry targets
 *                   does not have.
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
    } else if (check === "mappings") {
      checkMappings(model, results);
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

/**
 * The items of an output as the engine's deserializer reads them
 * (`OutputParameterDeserializer`): the first entry of a list, and a non-empty
 * map whose entries are all objects, without a string `type`, as named
 * declarations (the properties of an object); anything else as one
 * declaration. Each comes with its path in the document.
 */
function itemsOf(node, path) {
  const items = node.items;
  if (Array.isArray(items)) {
    return items.length > 0 ? [{ value: items[0], path: path.concat("items", 0) }] : [];
  }
  if (isObject(items) && Object.keys(items).length > 0 && typeof items.type !== "string"
      && Object.values(items).every(isObject)) {
    return entries(items).map((e) => ({
      value: e.value, path: path.concat("items", ...e.locator), named: true,
    }));
  }
  return [{ value: items, path: path.concat("items") }];
}

/** Collect the `schema:` references of an output declaration. */
function walkOutput(node, path, refs) {
  if (!isObject(node)) {
    return;
  }
  if (typeof node.schema === "string") {
    refs.push({ kind: "schema", name: node.schema, path: path.concat("schema"), output: true });
  }
  setNames(node).forEach((name, index) => {
    refs.push({
      kind: "mappingSet", name,
      path: Array.isArray(node.mappingSets) ? path.concat("mappingSets", index)
        : path.concat("mappingSets"),
    });
  });
  for (const item of itemsOf(node, path)) {
    walkOutput(item.value, item.path, refs);
  }
  walkOutput(node.values, path.concat("values"), refs);
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
  const mappingSets = dictionary(defs.mappingSets);
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
    if (Array.isArray(unit.mappings)) {
      unit.mappings.forEach((mapping, index) => {
        if (isObject(mapping) && Array.isArray(mapping.mappingSets)) {
          mapping.mappingSets.forEach((name, setIndex) => {
            refs.push({
              kind: "mappingSet", name,
              path: unitPath.concat("mappings", index, "mappingSets", setIndex),
            });
          });
        }
      });
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
    // Mapped (call-mode) outputs are a list; orchestrated and mock outputs a map.
    // The engine's rule: an MCP tool's outputs are mapped in list form (it may infer its
    // call; the map form is a mock's) or with `ref`; a flow's or REST operation's when it has
    // `call` or `ref`. A `ref` unit's own outputs map its flow's result. Otherwise a mock.
    const runs = (value) => value !== undefined && value !== null;
    const mapped = !orchestrated && (owner.unitKind === "tool"
      ? Array.isArray(unit.outputParameters) || runs(unit.ref)
      : runs(unit.call) || runs(unit.ref));
    units.push({ name: u.key, unit, path: unitPath, refs, orchestrated, mapped });
  }

  return { owner, sets, schemas, mappingSets, defsPath, setRefs, schemaRefs, units };
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
    } else if (ref.kind === "mappingSet" && !(ref.name in model.mappingSets)) {
      let message;
      if (ref.name in model.schemas) {
        message = "'" + ref.name + "' is a schema, not a mapping set. Use it with 'schema: "
          + ref.name + "'.";
      } else if (ref.name in model.sets) {
        message = "'" + ref.name + "' is an input parameter set, not a mapping set. Use it with"
          + " 'inputParameterSets: [" + ref.name + "]' on the " + model.owner.unitKind + ".";
      } else {
        message = "Unknown mapping set '" + ref.name + "'. " + label + " declares "
          + declared(model.mappingSets) + ".";
      }
      results.push({ message, path: ref.path });
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
  const usedMappingSets = new Set();
  const pending = [];
  const use = (ref) => {
    if (ref.kind === "mappingSet") {
      usedMappingSets.add(ref.name);
      return;
    }
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
  // Sets listed in an MCP resource output are rejected there, but used, as in the engine
  if (model.owner.unitKind === "tool") {
    for (const r of unitsAt(model.owner.node.resources, model.owner.path.concat("resources"))) {
      const refs = [];
      for (const p of entries(isObject(r.value) ? r.value.outputParameters : null)) {
        walkOutput(p.value, r.path.concat("outputParameters", ...p.locator), refs);
      }
      refs.filter((ref) => ref.kind === "mappingSet").forEach(use);
    }
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
  for (const name of Object.keys(model.mappingSets)) {
    if (!usedMappingSets.has(name)) {
      results.push({
        message: label + " declares the mapping set '" + name + "', which no " + unitKind
          + " uses.",
        path: model.defsPath.concat("mappingSets", name),
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
 * The input-only keywords a schema declares, on itself and on what an output
 * keeps of it: the properties of an object, and the items of an array (in an
 * orchestrated output, only when they are objects with properties; in a mapped
 * output, always). Iterative, and each schema is visited once, so cycles and
 * long chains end.
 */
function droppedKeywords(model, name, allItems) {
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
      if (allItems || (isObject(items) && items.type === "object"
          && isObject(items.properties) && Object.keys(items.properties).length > 0)) {
        pending.push(node.items);
      }
    }
  }
  return INPUT_ONLY_KEYWORDS.filter((keyword) => found.has(keyword));
}

function checkOutputKeywords(model, results) {
  for (const unit of model.units) {
    if (!unit.orchestrated && !unit.mapped) {
      continue;
    }
    for (const ref of unit.refs) {
      if (!ref.output || typeof ref.name !== "string" || !(ref.name in model.schemas)) {
        continue;
      }
      const dropped = droppedKeywords(model, ref.name, unit.mapped);
      if (dropped.length === 0) {
        continue;
      }
      results.push({
        message: unit.orchestrated
          ? "Schema '" + ref.name + "' is used in an orchestrated output, which does"
            + " not carry its keywords " + dropped.join(", ") + ". An output keeps only the"
            + " schema's type, properties, and items that are objects with properties."
          : "Schema '" + ref.name + "' is used in a mapped output, which does not carry its"
            + " keywords " + dropped.join(", ") + ". A mapped output keeps only the schema's"
            + " type, properties and items, each with its mapping.",
        path: ref.path,
      });
    }
  }
}

/**
 * Whether a declaration binds anything with `mappings`. An empty map binds
 * nothing, as in the engine (the JSON Schema rejects it on its own).
 */
function declaresMappings(node) {
  return isObject(node.mappings) && Object.keys(node.mappings).length > 0;
}

/**
 * The mapping set names a declaration lists: a single name reads as a
 * one-name list, as in the engine (the JSON Schema wants a list).
 */
function setNames(node) {
  // As the engine reads them: no value is no list, and a number, a boolean or null names a set
  // by its text (an unknown one); an object stays as is, which no set is named after
  const name = (value) => (value === null || typeof value !== "object" ? String(value) : value);
  const sets = node.mappingSets;
  if (sets === undefined || sets === null) {
    return [];
  }
  return Array.isArray(sets) ? sets.map(name) : [name(sets)];
}

/** The path of a mapping set a declaration lists, for a finding. */
function setPath(node, path, set) {
  return Array.isArray(node.mappingSets)
    ? path.concat("mappingSets", node.mappingSets.indexOf(set)) : path.concat("mappingSets");
}

/** Whether a declaration lists mapping sets. */
function declaresMappingSets(node) {
  return setNames(node).length > 0;
}

/**
 * The mappings that bind a schema reference, as the engine merges them
 * (#1133): its mapping sets by path, in order, a later set winning, then its
 * own `mappings`, which win last. `origins` gives the set each path comes
 * from; `unresolved` says a listed set is not declared (the references check
 * reports it), so what it would bind is unknown; `setEntries` counts the
 * entries of the listed sets, which each use is charged, as in the engine.
 */
function mergedMappings(model, node) {
  const values = Object.create(null);
  const origins = Object.create(null);
  let unresolved = false;
  let setEntries = 0;
  for (const name of setNames(node)) {
    if (typeof name !== "string" || !(name in model.mappingSets)) {
      unresolved = true;
      continue;
    }
    // A declared set that is not a map holds no mapping, as in the engine
    const set = isObject(model.mappingSets[name]) ? model.mappingSets[name] : {};
    setEntries += Object.keys(set).length;
    for (const key of Object.keys(set)) {
      values[key] = set[key];
      origins[key] = name;
    }
  }
  for (const key of Object.keys(isObject(node.mappings) ? node.mappings : {})) {
    values[key] = node.mappings[key];
    delete origins[key];
  }
  return { values, origins, unresolved, setEntries };
}

/** Whether a property name can be addressed by a `mappings` path. */
function addressable(name) {
  return !/[.[\]]/.test(name);
}

/**
 * Keys a mapped output takes beside `schema:`, as the engine's check. `$ref` is reported on
 * its own, by `walkMapped`.
 */
const BESIDE_MAPPED_SCHEMA = ["schema", "name", "$ref", "mappings", "mappingSets", "mapping"];

/**
 * Upper bound on the declarations the mapped bindings of one namespace expand to together, each
 * charged the size of its schema and the entries of its mapping sets as the engine charges it. The engine spends the same bound
 * across the namespace on everything it expands (inputs and orchestrated outputs too); this
 * check counts the mapped bindings only, so a namespace it accepts may still exceed the
 * engine's budget, never the reverse.
 */
const MAX_BINDING_NODES = 100000;

/** Upper bound on the nesting depth of a binding walk, as the engine's. */
const MAX_BINDING_DEPTH = 64;

/** Whether a declaration is an object with properties. */
function withProperties(schema) {
  return isObject(schema) && schema.type === "object" && isObject(schema.properties)
    && Object.keys(schema.properties).length > 0;
}

/**
 * Bind a schema reference of a mapped output, as the engine does: walk the
 * schema, following `schema:` references, and record its bindable paths, the
 * scalars, arrays and objects without properties its `mappings` leave
 * unbound, the bindings the runtime never reads, and the property names a path
 * cannot address. The walk stops at a schema already on its path (a cycle,
 * which the cycles check reports) and past the engine's depth bound: `stopped`
 * then says why, and the binding is not judged. It does not enter a subtree
 * the runtime never reads unless a mapping reaches into it, so a large schema
 * below nested arrays costs nothing to walk.
 *
 * @param rootIsItems whether the schema is an array used as the items of
 *   another array, whose own items the runtime never shapes
 */
function bindingOf(model, node, mappings, rootIsItems) {
  const keys = Object.keys(mappings);
  // Whether a mapping names the path or a path below it
  const reached = (path) => keys.some((key) => key === path || key.startsWith(path + ".")
    || key.startsWith(path + "["));
  const binding = {
    paths: [], unbound: [], unaddressable: [], ineffective: [], used: new Set(),
    stopped: null,
  };
  // Follow references from a declaration, recording each schema it passes
  // through; null when one of them is already on the walk's path.
  const target = (declaration, onPath, entered) => {
    let current = declaration;
    while (isObject(current) && typeof current.schema === "string"
        && current.schema in model.schemas) {
      if (onPath.has(current.schema)) {
        return null;
      }
      onPath.add(current.schema);
      entered.push(current.schema);
      current = model.schemas[current.schema];
    }
    return current;
  };
  const visit = (declaration, path, depth, unread, onPath) => {
    if (binding.stopped || (unread && !reached(path))) {
      // Nothing below binds what the runtime never reads: nothing to judge there
      return;
    }
    if (depth > MAX_BINDING_DEPTH) {
      binding.stopped = "depth";
      return;
    }
    const entered = [];
    const schema = target(declaration, onPath, entered);
    if (schema === null) {
      binding.stopped = "cycle";
      return;
    }
    if (isObject(schema)) {
      const objectWithProperties = withProperties(schema);
      if (path !== "") {
        const bound = path in mappings;
        if (bound) {
          binding.used.add(path);
        }
        if (unread) {
          if (bound) {
            binding.ineffective.push(path);
          }
        } else if (bound && path.endsWith("[]") && objectWithProperties) {
          binding.paths.push(path);
          binding.ineffective.push(path);
        } else {
          binding.paths.push(path);
          if (!bound && !objectWithProperties && !path.endsWith("[]")) {
            binding.unbound.push(path);
          }
        }
      }
      if (schema.type === "object") {
        for (const p of entries(isObject(schema.properties) ? schema.properties : null)) {
          if (!addressable(p.key)) {
            // Below nested arrays nothing is read, so nothing needs a path, as in the engine
            if (!unread) {
              binding.unaddressable.push(p.key);
            }
            continue;
          }
          visit(p.value, path === "" ? p.key : path + "." + p.key, depth + 1, unread, onPath);
        }
      } else if (schema.type === "array" && isObject(schema.items)) {
        const itemsUnread = unread || path.endsWith("[]") || (path === "" && rootIsItems);
        visit(schema.items, path + "[]", depth + 1, itemsUnread, onPath);
      }
    }
    for (const name of entered) {
      onPath.delete(name);
    }
  };
  visit(model.schemas[node.schema], "", 0, false, new Set());
  return binding;
}

/** The declaration a schema name stands for, following references, or undefined. */
function rootOf(model, name) {
  let current = model.schemas[name];
  const seen = new Set([name]);
  while (isObject(current) && typeof current.schema === "string"
      && current.schema in model.schemas && !seen.has(current.schema)) {
    seen.add(current.schema);
    current = model.schemas[current.schema];
  }
  return current;
}

/**
 * Report what is wrong with the binding of one schema reference.
 *
 * @param position where the output sits: "top" (a unit's output, an object
 *   read from the response root), "items" (array items or map values, read
 *   from each element) or "property"
 */
/**
 * Number of parameter declarations a schema expands to, as the engine's
 * `size(name)` counts them (each declaration, its items, values and
 * properties, through references, capped just past the budget), once per
 * schema; or null for a schema in a cycle, or met on a chain of
 * `MAX_BINDING_DEPTH` references or more while sizing: every schema of such a
 * chain is left unsized, its near end included, and which schemas are met that
 * way depends on the order schemas are sized (so is whether a cycle member
 * reached only through a schema already unsized gets a size, and is charged).
 * A reference to an unsized schema counts one, as in the engine. Only a
 * document the engine rejects for depth or for a cycle has such a chain; the engine's depth bound also counts inline nesting, which
 * this does not, so on such a document the budget may be reported where the
 * engine reports the depth. The references are followed iteratively, so a long
 * chain cannot overflow the call stack.
 */
function schemaSize(model, name, budget) {
  const { sizes, unsized } = budget;
  const referenced = (schema) => (model.schemaRefs.get(schema) || [])
    .map((ref) => ref.name)
    .filter((ref) => typeof ref === "string" && ref in model.schemas);
  const stack = [{ name, refs: referenced(name), next: 0 }];
  const chain = [name];
  while (stack.length > 0 && !sizes.has(name) && !unsized.has(name)) {
    const top = stack[stack.length - 1];
    if (top.next < top.refs.length) {
      const ref = top.refs[top.next];
      top.next += 1;
      if (sizes.has(ref) || unsized.has(ref)) {
        continue;
      }
      const at = chain.indexOf(ref);
      if (at >= 0) {
        chain.slice(at).forEach((member) => unsized.add(member));
      } else if (chain.length >= MAX_BINDING_DEPTH) {
        chain.forEach((member) => unsized.add(member));
      } else {
        chain.push(ref);
        stack.push({ name: ref, refs: referenced(ref), next: 0 });
      }
      continue;
    }
    stack.pop();
    chain.pop();
    if (!unsized.has(top.name)) {
      sizes.set(top.name, declarationSize(model, model.schemas[top.name], sizes));
    }
  }
  return sizes.has(name) ? sizes.get(name) : null;
}

/**
 * Number of declarations of one declaration, its references counted from the
 * sizes already computed (one for a schema that has none: not expanded).
 */
function declarationSize(model, node, sizes) {
  if (!isObject(node)) {
    return 0;
  }
  if (typeof node.schema === "string") {
    return sizes.has(node.schema) ? sizes.get(node.schema) : 1;
  }
  let total = 1 + declarationSize(model, node.items, sizes)
    + declarationSize(model, node.values, sizes);
  for (const p of entries(isObject(node.properties) ? node.properties : null)) {
    total = Math.min(total + declarationSize(model, p.value, sizes), MAX_BINDING_NODES + 1);
  }
  return Math.min(total, MAX_BINDING_NODES + 1);
}

function checkBinding(model, node, path, position, results, budget) {
  const name = node.schema;
  const prefix = "'schema: " + name + "' ";
  // As the engine: beside `schema:`, a mapped output takes only its binding
  const extra = Object.keys(node).filter((key) => !BESIDE_MAPPED_SCHEMA.includes(key));
  if (extra.length > 0) {
    results.push({
      message: prefix + "takes only 'mappings', 'mappingSets' and 'mapping' in a mapped"
        + " output, found [" + extra.join(", ") + "].",
      path: path.concat(extra[0]),
    });
  }
  if (!(name in model.schemas)) {
    return; // reported by the references check
  }
  if (position === "unread") {
    results.push({
      message: prefix + "sits in the items of a nested array, which the runtime passes"
        + " through as they are: its bindings are never read. Remove it, or bind the outer"
        + " items instead.",
      path: path.concat("schema"),
    });
    return;
  }
  // Charged as the engine charges a binding: the size of the schema it expands and the
  // entries of the sets it lists, before anything is bound or reported. A schema in a cycle or
  // on too long a chain of references is not charged, as the engine does not expand it: the
  // binding walk stops on it, and the cycles check or the engine reports it
  const merged = mergedMappings(model, node);
  const size = schemaSize(model, name, budget);
  budget.visited += (size === null ? 0 : size) + merged.setEntries;
  if (budget.visited > MAX_BINDING_NODES) {
    if (!budget.reported) {
      // Once per namespace, as the engine reports its budget
      budget.reported = true;
      results.push({
        message: model.owner.label + ": its definitions expand to more than "
          + MAX_BINDING_NODES + " parameter declarations. Expansion is bounded, so that a"
          + " document cannot multiply itself without limit.",
        path: path.concat("schema"),
      });
    }
    return;
  }
  const root = rootOf(model, name);
  const object = isObject(root) && root.type === "object";
  if (position === "top" && object && !withProperties(root)) {
    results.push({
      message: prefix + "is an object without properties, which as an output always returns"
        + " an empty object. Use it as a property, with a 'mapping'.",
      path: path.concat("schema"),
    });
    return;
  }
  const binding = bindingOf(model, node, merged.values, position === "items" && isObject(root)
    && root.type === "array");
  const empty = Object.keys(merged.values).length === 0;
  if (empty && binding.unbound.length > 0 && !binding.stopped && !merged.unresolved) {
    results.push({
      message: prefix + "in a mapped (call-mode) output needs 'mappings' or 'mappingSets', the"
        + " mapping of each path of the schema.",
      path: path.concat("schema"),
    });
    return;
  }
  if (binding.stopped === "cycle" || binding.stopped === "depth") {
    return; // reported by the cycles check, or by the engine's depth bound
  }
  const rootWithProperties = withProperties(root);
  if (typeof node.mapping === "string" && object && position !== "property"
      && (rootWithProperties || position === "top")) {
    results.push({
      message: "'mapping' beside " + prefix.trim() + " has no effect: "
        + (position === "top" ? "an output object is read from the response root"
          : "object items are read from each element")
        + ". Remove it, or move the object under a property.",
      path: path.concat("mapping"),
    });
  }
  if (typeof node.mapping !== "string" && position !== "items" && !rootWithProperties) {
    binding.unbound.unshift("(the output itself: set 'mapping' beside 'schema:')");
  }
  if (binding.unaddressable.length > 0) {
    results.push({
      message: prefix + "has properties that a path cannot address, as their name contains"
        + " '.', '[' or ']': " + binding.unaddressable.join(", ") + ". Declare the output"
        + " inline.",
      path: path.concat("schema"),
    });
  }
  if (binding.unbound.length > 0 && !merged.unresolved) {
    // A set that is not declared is reported by the references check
    results.push({
      message: prefix + "leaves paths unbound: " + binding.unbound.join(", ") + ". Give each"
        + " scalar, array and object without properties a mapping in 'mappings' or"
        + " 'mappingSets'.",
      path: path.concat(isObject(node.mappings) || !declaresMappingSets(node) ? "mappings"
        : "mappingSets"),
    });
  }
  if (binding.ineffective.length > 0) {
    const fromSet = binding.ineffective.map((key) => merged.origins[key])
      .find((set) => set !== undefined);
    results.push({
      message: prefix + "binds " + binding.ineffective.map((key) => key
        + (merged.origins[key] === undefined ? "" : " (mapping set '" + merged.origins[key]
          + "')")).join(", ") + ", which the runtime never reads: object items are read from"
        + " each element, and the items of nested arrays are passed through as they are."
        + " Remove them, or bind this schema with other mappings.",
      path: fromSet === undefined ? path.concat("mappings") : setPath(node, path, fromSet),
    });
  }
  for (const key of Object.keys(merged.values)) {
    const set = merged.origins[key];
    if (!binding.used.has(key)) {
      results.push({
        message: prefix + "has no path '" + key + "'"
          + (set === undefined ? "" : " (mapping set '" + set + "')") + ". Its paths are "
          + (binding.paths.length === 0 ? "none" : binding.paths.join(", ")) + ".",
        path: set === undefined ? path.concat("mappings", key) : setPath(node, path, set),
      });
    } else if (set === undefined && (typeof node.mappings[key] !== "string"
        || !node.mappings[key].trim().startsWith("$"))) {
      // A set's values are checked once, where it is declared
      results.push({
        message: prefix + "binds '" + key + "' to a value that is not a JSONPath (starting"
          + " with '$'). A static 'value' is declared inline.",
        path: path.concat("mappings", key),
      });
    }
  }
}

/** Walk a mapped output and check each schema reference and stray `mappings`. */
function walkMapped(model, node, path, position, results, budget) {
  if (!isObject(node)) {
    return;
  }
  // As the engine: the JSON Schema leaves a ref unit's outputs open, so it may not reject it
  if (Object.prototype.hasOwnProperty.call(node, "$ref")) {
    results.push({
      message: "'$ref' is not supported. Reference a schema of the definitions by its bare name,"
        + " with 'schema: name'.",
      path: path.concat("$ref"),
    });
  }
  if (typeof node.schema === "string") {
    checkBinding(model, node, path, position, results, budget);
    return;
  }
  if (declaresMappings(node)) {
    results.push({
      message: "'mappings' binds a schema, so it needs 'schema:' beside it. Name the schema,"
        + " or declare a 'mapping' on each leaf inline.",
      path: path.concat("mappings"),
    });
  }
  if (declaresMappingSets(node)) {
    results.push({
      message: "'mappingSets' binds a schema, so it needs 'schema:' beside it. Name the"
        + " schema, or declare a 'mapping' on each leaf inline.",
      path: path.concat("mappingSets"),
    });
  }
  const unread = position === "unread";
  // The items of an array that is itself items are passed through as they are
  const nested = position === "items" && node.type === "array";
  for (const item of itemsOf(node, path)) {
    // Named items are the properties of an object read from each element
    walkMapped(model, item.value, item.path, unread || nested ? "unread"
      : (item.named ? "property" : "items"), results, budget);
  }
  walkMapped(model, node.values, path.concat("values"), unread ? "unread" : "items", results,
    budget);
  for (const p of entries(isObject(node.properties) ? node.properties : null)) {
    walkMapped(model, p.value, path.concat("properties", ...p.locator),
      unread ? "unread" : "property", results, budget);
  }
}

/**
 * Whether an output, or one of its nested declarations, uses `schema:`,
 * `mappings` or `mappingSets`.
 */
function bindsDefinitions(node) {
  if (!isObject(node)) {
    return false;
  }
  if (typeof node.schema === "string" || declaresMappings(node) || declaresMappingSets(node)) {
    return true;
  }
  if (itemsOf(node, []).some((item) => bindsDefinitions(item.value))
      || bindsDefinitions(node.values)) {
    return true;
  }
  return entries(isObject(node.properties) ? node.properties : null)
    .some((p) => bindsDefinitions(p.value));
}

/**
 * Whether an orchestrated output declaration has a property path, following
 * `schema:` references. An object without properties takes any path, as the
 * runtime sets it whole.
 */
function hasOutputPath(model, declaration, segments) {
  let current = declaration;
  for (const segment of segments) {
    if (isObject(current) && typeof current.schema === "string") {
      current = rootOf(model, current.schema);
    }
    if (!isObject(current) || current.type !== "object") {
      return false;
    }
    if (!isObject(current.properties) || Object.keys(current.properties).length === 0) {
      return true;
    }
    current = Object.prototype.hasOwnProperty.call(current.properties, segment)
      ? current.properties[segment] : undefined;
  }
  return current !== undefined;
}

/**
 * Check the steps-mode `mappings` entries of a unit that list `mappingSets`
 * (#1150), as the engine expands them: the entry roots the merged sets' paths
 * with its `target` and `value`, no path may go through array items, and when
 * the target names an output of the unit, every leaf path must exist in it.
 */
function checkStepMappingSets(model, unit, results) {
  if (!Array.isArray(unit.unit.mappings)) {
    return;
  }
  unit.unit.mappings.forEach((mapping, index) => {
    if (!isObject(mapping) || !declaresMappingSets(mapping)) {
      return;
    }
    const path = unit.path.concat("mappings", index);
    const merged = mergedMappings(model, { mappingSets: mapping.mappingSets });
    if (merged.unresolved) {
      return; // reported by the references check
    }
    const root = typeof mapping.value === "string" ? mapping.value.trim() : "";
    // `$.` and a step name, as the runtime reads a step mapping's value
    if (typeof mapping.target !== "string" || !/^\$\.[^.[\]\s]+/.test(root)) {
      results.push({
        message: "'mappingSets' needs 'target' and a 'value' JSONPath ('$.<step>...'), the"
          + " roots of the sets' paths.",
        path,
      });
      return;
    }
    const paths = Object.keys(merged.values);
    const label = (key) => key + " (mapping set '" + merged.origins[key] + "')";
    const items = paths.filter((key) => key.includes("[]"));
    if (items.length > 0) {
      results.push({
        message: "[" + items.map(label).join(", ") + "] bind array items, which a steps-mode"
          + " mapping cannot reach: it sets each target whole. Map the array whole, or bind its"
          + " items in a call-mode output.",
        path: path.concat("mappingSets"),
      });
      return;
    }
    const outputs = unit.unit.outputParameters;
    const output = !mapping.target.includes(".") && isObject(outputs)
      && Object.prototype.hasOwnProperty.call(outputs, mapping.target)
      ? outputs[mapping.target] : undefined;
    if (output === undefined) {
      return;
    }
    const missing = paths
      .filter((key) => !paths.some((other) => other.startsWith(key + ".")))
      .filter((key) => !hasOutputPath(model, output, key.split(".")));
    if (missing.length > 0) {
      results.push({
        message: "Output '" + mapping.target + "' has no path [" + missing.map(label).join(", ")
          + "].",
        path: path.concat("mappingSets"),
      });
    }
  });
}

function checkMappings(model, results) {
  const budget = { visited: 0, reported: false, sizes: new Map(), unsized: new Set() };
  // A mapping set's values are checked once, where it is declared; its paths at each use
  for (const name of Object.keys(model.mappingSets)) {
    const set = model.mappingSets[name];
    if (!isObject(set) || Object.keys(set).length === 0) {
      results.push({
        message: model.owner.label + ", mapping set '" + name + "' declares no mapping."
          + " Declare it as a map of paths to JSONPaths.",
        path: model.defsPath.concat("mappingSets", name),
      });
      continue;
    }
    for (const key of Object.keys(set)) {
      if (typeof set[key] !== "string" || !set[key].trim().startsWith("$")) {
        results.push({
          message: model.owner.label + ", mapping set '" + name + "' binds '" + key + "' to a"
            + " value that is not a JSONPath (starting with '$'). A static 'value' is declared"
            + " inline.",
          path: model.defsPath.concat("mappingSets", name, key),
        });
      }
    }
  }
  for (const unit of model.units) {
    if (!unit.mapped && !unit.orchestrated) {
      // A mock returns static values: nothing there can bind a schema
      for (const p of entries(unit.unit.outputParameters)) {
        if (bindsDefinitions(p.value)) {
          results.push({
            message: "'schema:', 'mappings' and 'mappingSets' are not allowed in a mock"
              + " output, which returns static values. Declare the output inline.",
            path: unit.path.concat("outputParameters", ...p.locator),
          });
        }
      }
      continue;
    }
    if (unit.orchestrated) {
      checkStepMappingSets(model, unit, results);
    }
    if (!unit.mapped) {
      continue;
    }
    for (const p of entries(unit.unit.outputParameters)) {
      walkMapped(model, p.value, unit.path.concat("outputParameters", ...p.locator), "top",
        results, budget);
    }
  }
  // Definitions do not reach MCP resources: a reference there would never be expanded
  if (model.owner.unitKind === "tool") {
    for (const r of unitsAt(model.owner.node.resources, model.owner.path.concat("resources"))) {
      if (!isObject(r.value)) {
        continue;
      }
      if (entries(r.value.outputParameters).some((p) => bindsDefinitions(p.value))) {
        results.push({
          message: model.owner.label + ", resource '" + r.key + "': 'schema:', 'mappings'"
            + " and 'mappingSets' are not supported in an MCP resource output. Declare the"
            + " output inline.",
          path: r.path.concat("outputParameters"),
        });
      }
    }
  }
}
