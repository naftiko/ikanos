/**
 * Polychro rule function: step-names-unique
 *
 * `steps:` is a list, and later steps, templates and mappings read a step's
 * output under its `name`, so two steps of one list may not share a name. The
 * engine refuses such a list when the capability loads; this rule reports it
 * at lint time, on the second step that uses the name.
 *
 * Every `steps` list of the document is checked: those of MCP tools and
 * resources, REST operations and aggregate flows, the `tracks` of a `parallel`
 * step and the `steps` of a `sequence`. A list is recognised by its
 * items, objects that declare a `type`, so a `steps` key inside a `with` map is
 * left alone.
 */
function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isStepList(value) {
  return Array.isArray(value) && value.length > 0
    && value.every((item) => isObject(item) && typeof item.type === "string");
}

function check(steps, path, results) {
  const seen = new Map();
  steps.forEach((step, index) => {
    if (typeof step.name !== "string" || step.name.length === 0) {
      return;
    }
    if (seen.has(step.name)) {
      results.push({
        message: "Step name '" + step.name + "' is already used by step " + (seen.get(step.name) + 1)
          + " of this list. Step names must be unique within a 'steps' list.",
        path: path.concat(index, "name"),
      });
    } else {
      seen.set(step.name, index);
    }
  });
}

function walk(node, path, results) {
  if (Array.isArray(node)) {
    node.forEach((item, index) => walk(item, path.concat(index), results));
    return;
  }
  if (!isObject(node)) {
    return;
  }
  for (const key of Object.keys(node)) {
    const value = node[key];
    if ((key === "steps" || key === "tracks") && isStepList(value)) {
      check(value, path.concat(key), results);
    }
    walk(value, path.concat(key), results);
  }
}

export default function stepNamesUnique(targetVal) {
  if (!isObject(targetVal)) {
    return;
  }
  const results = [];
  walk(targetVal, [], results);
  return results;
}
