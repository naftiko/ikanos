/**
 * Spectral custom function: unique-namespaces
 *
 * A namespace has one owner across the whole capability: root and capability
 * `consumes`, `capability.exposes`, `capability.aggregates`, and root and
 * capability `binds`. `call: namespace.name` resolves against both consumed
 * adapters and aggregate flows, so a name shared by two sections would make
 * the target ambiguous.
 *
 * Imported entries are checked under their effective namespace: the `as`
 * alias, or the `import` name when there is no alias.
 */
function effectiveNamespace(entry) {
  if (!entry || typeof entry !== "object") {
    return undefined;
  }
  if (typeof entry.namespace === "string" && entry.namespace.length > 0) {
    return entry.namespace;
  }
  if (typeof entry.from === "string") {
    if (typeof entry.as === "string" && entry.as.length > 0) {
      return entry.as;
    }
    if (typeof entry.import === "string" && entry.import.length > 0) {
      return entry.import;
    }
  }
  return undefined;
}

export default function uniqueNamespaces(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const seen = new Map();
  const results = [];

  function addNamespace(path, value, kind) {
    if (typeof value !== "string" || value.length === 0) {
      return;
    }

    const prior = seen.get(value);
    if (prior === undefined) {
      seen.set(value, { path, kind });
      return;
    }

    const scope = prior.kind === kind ? kind : "adapter and bind";
    results.push({
      message:
        "Namespace '" +
        value +
        "' is already used at " +
        prior.path.join("/") +
        ". Namespaces must be globally unique across " +
        scope +
        " entries.",
      path,
    });
  }

  // Root consumes
  const rootConsumes = Array.isArray(targetVal.consumes) ? targetVal.consumes : [];
  for (let i = 0; i < rootConsumes.length; i += 1) {
    addNamespace(["consumes", i, "namespace"], effectiveNamespace(rootConsumes[i]), "adapter");
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object" ? targetVal.capability : {};

  // Capability consumes
  const capabilityConsumes = Array.isArray(capability.consumes) ? capability.consumes : [];
  for (let i = 0; i < capabilityConsumes.length; i += 1) {
    addNamespace(
      ["capability", "consumes", i, "namespace"],
      effectiveNamespace(capabilityConsumes[i]),
      "adapter",
    );
  }

  // Capability exposes
  const capabilityExposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  for (let i = 0; i < capabilityExposes.length; i += 1) {
    addNamespace(
      ["capability", "exposes", i, "namespace"],
      effectiveNamespace(capabilityExposes[i]),
      "adapter",
    );
  }

  // Capability aggregates (an array; older documents may use a map keyed by namespace)
  const aggregates = capability.aggregates;
  if (Array.isArray(aggregates)) {
    for (let i = 0; i < aggregates.length; i += 1) {
      addNamespace(
        ["capability", "aggregates", i, "namespace"],
        effectiveNamespace(aggregates[i]),
        "adapter",
      );
    }
  } else if (aggregates && typeof aggregates === "object") {
    for (const key of Object.keys(aggregates)) {
      addNamespace(
        ["capability", "aggregates", key, "namespace"],
        effectiveNamespace(aggregates[key]),
        "adapter",
      );
    }
  }

  // Root binds
  const rootBinds = Array.isArray(targetVal.binds) ? targetVal.binds : [];
  for (let i = 0; i < rootBinds.length; i += 1) {
    addNamespace(["binds", i, "namespace"], effectiveNamespace(rootBinds[i]), "bind");
  }

  // Capability binds
  const capabilityBinds = Array.isArray(capability.binds) ? capability.binds : [];
  for (let i = 0; i < capabilityBinds.length; i += 1) {
    addNamespace(
      ["capability", "binds", i, "namespace"],
      effectiveNamespace(capabilityBinds[i]),
      "bind",
    );
  }

  return results;
};
