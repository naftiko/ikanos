/**
 * Spectral custom function: standalone-no-imports
 *
 * Enforces §11.3 of the unified-import-mechanism blueprint:
 * standalone files (documents without a `capability` key that have a root-level
 * section array) must not contain import entries (entries with a `from` field).
 *
 * This is a defense-in-depth layer on top of the JSON Schema root-level `oneOf`,
 * which structurally forbids `ImportEntry` variants in standalone branches.
 */
export default function standaloneNoImports(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  // Only applies to standalone documents (no capability key)
  if (targetVal.capability) {
    return;
  }

  const results = [];
  const sections = ["consumes", "exposes", "aggregates", "binds"];

  for (const section of sections) {
    const entries = targetVal[section];
    if (!Array.isArray(entries)) {
      continue;
    }

    for (let i = 0; i < entries.length; i += 1) {
      const entry = entries[i];
      if (entry && typeof entry.from === "string" && entry.from.length > 0) {
        results.push({
          message:
            "Standalone " +
            section +
            " files must not contain import entries. " +
            "Entry at index " +
            i +
            " has a 'from' field — imports are only allowed in capability documents.",
          path: [section, i, "from"],
        });
      }
    }
  }

  return results;
}
