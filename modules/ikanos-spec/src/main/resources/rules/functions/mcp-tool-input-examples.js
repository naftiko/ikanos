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
 *     input, or not `true` / `false` for a `boolean` input. Numbers follow the
 *     syntax the engine converts (no hexadecimal, no surrounding spaces), and an
 *     integral decimal such as `25.0` is an integer;
 *   - the input declares `enum` and the example is not one of its values,
 *     compared as numbers for a `number` or `integer` input (`1` matches `"1.0"`);
 *   - it is an unquoted integer beyond 2^53, which the YAML reader rounds before
 *     this rule sees it: quoted, it is checked exactly. An enum with such a value
 *     is not compared.
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
  if (isInexact(example)) {
    return "is an integer too large for the linter to read exactly; quote it";
  }
  const text = String(example);
  const numeric = type === "number" || type === "integer";
  const decimal = numeric ? parseDecimal(text) : null;
  if (type === "number" && !decimal) {
    return "is not a number";
  }
  if (type === "integer" && !(decimal && decimal.exponent >= 0)) {
    return "is not an integer";
  }
  if (type === "boolean" && text !== "true" && text !== "false") {
    return "is not true or false";
  }
  // An enum value that was not read exactly cannot be compared: leave it to the reader.
  if (Array.isArray(schema.enum) && !schema.enum.some(isInexact)
      && !schema.enum.some((value) => sameValue(value, text, decimal))) {
    return "is not one of the input's enum values";
  }
  return null;
}

/**
 * Whether a value is an unquoted integer beyond 2^53, which the YAML reader has already rounded
 * to the nearest double: the rule no longer sees the authored digits, while the engine does.
 */
function isInexact(value) {
  return typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value);
}

/**
 * Whether an enum value and an example are the same value once the engine emits them with the
 * input's type: numerically equal for a number or integer input (so `1.0` matches `1`), equal
 * text otherwise.
 */
function sameValue(enumValue, text, decimal) {
  const enumText = String(enumValue);
  if (decimal) {
    const enumDecimal = parseDecimal(enumText);
    if (enumDecimal) {
      return enumDecimal.sign === decimal.sign && enumDecimal.digits === decimal.digits
        && enumDecimal.exponent === decimal.exponent;
    }
  }
  return enumText === text;
}

/**
 * Parses the decimal syntax the engine accepts for number and integer values (Java's
 * `BigDecimal`: an optional sign, digits with an optional point, an optional exponent, no
 * surrounding spaces), or returns null. The value is kept exact, as digits without leading or
 * trailing zeros and a power of ten, so arbitrary precision survives and integrality is
 * `exponent >= 0`. Zero is `{ sign: "", digits: "0", exponent: 0 }`.
 */
function parseDecimal(text) {
  const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(text);
  if (!match || (match[2] + (match[3] || "")) === "") {
    return null;
  }
  const fraction = match[3] || "";
  let digits = (match[2] + fraction).replace(/^0+/, "");
  if (digits === "") {
    return { sign: "", digits: "0", exponent: 0 };
  }
  let exponent = Number(match[4] || "0") - fraction.length;
  const trimmed = digits.replace(/0+$/, "");
  exponent += digits.length - trimmed.length;
  digits = trimmed;
  return { sign: match[1] === "-" ? "-" : "", digits, exponent };
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
