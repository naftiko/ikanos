/**
 * Spectral custom function: import-alias-unique
 *
 * Enforces §11.1 `ikanos-import-unique-alias` of the unified-import-mechanism blueprint:
 * within each section of a capability, the effective namespace of imported entries
 * (the `as` alias, or `import` when no `as`) must be unique.
 *
 * Also covers `ikanos-import-alias-when-needed` (info): suggests `as:` when two
 * imports in the same section target the same `import:` namespace.
 */
export default function importAliasUnique(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const results = [];
  const capability =
    targetVal.capability && typeof targetVal.capability === "object" ? targetVal.capability : {};

  const sectionPaths = [
    { key: "consumes", base: ["capability", "consumes"] },
    { key: "exposes", base: ["capability", "exposes"] },
    { key: "aggregates", base: ["capability", "aggregates"] },
    { key: "binds", base: ["capability", "binds"] },
  ];

  for (const { key, base } of sectionPaths) {
    const entries = Array.isArray(capability[key]) ? capability[key] : [];
    const effectiveNames = new Map(); // effectiveName -> { index, path }
    const importNames = new Map(); // importNamespace -> [indices]

    for (let i = 0; i < entries.length; i += 1) {
      const entry = entries[i];
      if (!entry || typeof entry.from !== "string") {
        continue; // not an import entry
      }

      // Effective namespace: `as` if present, else `import`
      const effective =
        typeof entry.as === "string" && entry.as.length > 0
          ? entry.as
          : entry.import;

      if (typeof effective === "string" && effective.length > 0) {
        const prior = effectiveNames.get(effective);
        if (prior !== undefined) {
          results.push({
            message:
              "Duplicate effective namespace '" +
              effective +
              "' in " +
              key +
              " imports: entry " +
              i +
              " conflicts with entry " +
              prior.index +
              ". Use distinct 'as' aliases to disambiguate.",
            path: [...base, i, entry.as ? "as" : "import"],
          });
        } else {
          effectiveNames.set(effective, { index: i, path: [...base, i] });
        }
      }

      // Track import namespaces for the suggestion rule
      const importNs = entry.import;
      if (typeof importNs === "string" && importNs.length > 0) {
        if (!importNames.has(importNs)) {
          importNames.set(importNs, []);
        }
        importNames.get(importNs).push(i);
      }
    }
  }

  return results;
}
