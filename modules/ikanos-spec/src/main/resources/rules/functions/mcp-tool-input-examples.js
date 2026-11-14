/**
 * Spectral custom function: mcp-tool-input-examples
 *
 * Enforces `ikanos-mcp-tool-input-examples`: an `examples` value of an MCP tool
 * input must be a valid value of that input, because the engine advertises it to
 * models as JSON Schema `examples` in the tool's inputSchema, and a wrong example
 * teaches the model to send a wrong argument. An example is reported when:
 *
 *   - the input is an object or an array (give examples on its `properties` or
 *     `items` instead);
 *   - it is not a number for a `number` input, not an integer for an `integer`
 *     input, or not `true` / `false` for a `boolean` input;
 *   - the input declares `enum` and the example is not one of its values.
 *
 * Walks MCP tool inputs (and their nested `items` / `properties`), aggregate flow
 * inputs, and the `definitions.schemas` of both, in a capability and at the root
 * of a shared section document.
 */
export default function mcpToolInputExamples(targetVal) {
  if (!isObject(targetVal)) {
    return;
  }
  const results = [];
  scanRoot(targetVal, [], results);
  if (isObject(targetVal.capability)) {
    scanRoot(targetVal.capability, ["capability"], results);
  }
  return results;
}

function scanRoot(root, basePath, results) {
  for (const adapter of entries(root.exposes)) {
    if (!isObject(adapter.value) || adapter.value.type !== "mcp") {
      continue;
    }
    const adapterPath = basePath.concat("exposes", ...adapter.locator);
    for (const tool of entries(adapter.value.tools)) {
      scanInputs(tool.value, adapterPath.concat("tools", ...tool.locator), results);
    }
    scanDefinitions(adapter.value.definitions, adapterPath.concat("definitions"), results);
  }
  for (const aggregate of entries(root.aggregates)) {
    if (!isObject(aggregate.value)) {
      continue;
    }
    const aggregatePath = basePath.concat("aggregates", ...aggregate.locator);
    for (const flow of entries(aggregate.value.flows)) {
      scanInputs(flow.value, aggregatePath.concat("flows", ...flow.locator), results);
    }
    scanDefinitions(aggregate.value.definitions, aggregatePath.concat("definitions"), results);
  }
}

function scanInputs(unit, unitPath, results) {
  if (!isObject(unit)) {
    return;
  }
  for (const input of entries(unit.inputParameters)) {
    walk(input.value, unitPath.concat("inputParameters", ...input.locator), results);
  }
}

function scanDefinitions(definitions, definitionsPath, results) {
  if (!isObject(definitions)) {
    return;
  }
  for (const schema of entries(definitions.schemas)) {
    walk(schema.value, definitionsPath.concat("schemas", ...schema.locator), results);
  }
  for (const set of entries(definitions.inputParameterSets)) {
    for (const input of entries(set.value)) {
      walk(input.value,
        definitionsPath.concat("inputParameterSets", ...set.locator, ...input.locator), results);
    }
  }
}

function walk(schema, path, results) {
  if (!isObject(schema)) {
    return;
  }
  if (Array.isArray(schema.examples)) {
    schema.examples.forEach((example, index) => {
      const problem = checkExample(schema, example);
      if (problem) {
        results.push({
          message: "Example " + JSON.stringify(example) + " " + problem + ".",
          path: path.concat("examples", index),
        });
      }
    });
  }
  if (isObject(schema.items)) {
    walk(schema.items, path.concat("items"), results);
  }
  for (const property of entries(schema.properties)) {
    walk(property.value, path.concat("properties", ...property.locator), results);
  }
}

function checkExample(schema, example) {
  const type = schema.type;
  if (type === "object" || type === "array") {
    return "is on an " + type + " input; give examples on its "
      + (type === "object" ? "properties" : "items") + " instead";
  }
  const text = String(example);
  if (type === "number" && !isNumber(text)) {
    return "is not a number";
  }
  if (type === "integer" && !/^-?\d+$/.test(text.trim())) {
    return "is not an integer";
  }
  if (type === "boolean" && text !== "true" && text !== "false") {
    return "is not true or false";
  }
  if (Array.isArray(schema.enum) && !schema.enum.map(String).includes(text)) {
    return "is not one of the input's enum values";
  }
  return null;
}

function isNumber(text) {
  return text.trim() !== "" && Number.isFinite(Number(text));
}

function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, index) => ({ value, locator: [index] }));
  }
  if (isObject(collection)) {
    return Object.keys(collection).map((key) => ({ value: collection[key], locator: [key] }));
  }
  return [];
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
