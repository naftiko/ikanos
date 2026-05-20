/**
 * Spectral custom function: aggregate-function-unique
 *
 * Enforces that function names within a single aggregate's 'functions' array
 * are unique. Duplicate function names would cause ambiguous 'call' and 'ref'
 * resolution. Applies to both standalone and capability documents.
 *
 * Given paths:
 *   - $.aggregates[*].functions
 *   - $.capability.aggregates[*].functions
 */
export default function aggregateFunctionUnique(targetVal, _opts, context) {
  if (!Array.isArray(targetVal)) {
    return;
  }

  const results = [];
  const seen = new Map(); // name -> first index

  for (let i = 0; i < targetVal.length; i += 1) {
    const fn = targetVal[i];
    const name = fn && typeof fn === "object" ? fn.name : undefined;
    if (typeof name !== "string" || name.length === 0) {
      continue;
    }

    const prior = seen.get(name);
    if (prior !== undefined) {
      results.push({
        message:
          "Duplicate function name '" +
          name +
          "' at index " +
          i +
          " (first seen at index " +
          prior +
          ").",
        path: [...context.path, i, "name"],
      });
    } else {
      seen.set(name, i);
    }
  }

  return results;
}
