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
    addNamespace(["consumes", i, "namespace"], rootConsumes[i] && rootConsumes[i].namespace, "adapter");
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object" ? targetVal.capability : {};

  // Capability consumes
  const capabilityConsumes = Array.isArray(capability.consumes) ? capability.consumes : [];
  for (let i = 0; i < capabilityConsumes.length; i += 1) {
    addNamespace(
      ["capability", "consumes", i, "namespace"],
      capabilityConsumes[i] && capabilityConsumes[i].namespace,
      "adapter",
    );
  }

  // Capability exposes
  const capabilityExposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  for (let i = 0; i < capabilityExposes.length; i += 1) {
    addNamespace(
      ["capability", "exposes", i, "namespace"],
      capabilityExposes[i] && capabilityExposes[i].namespace,
      "adapter",
    );
  }

  // Root binds
  const rootBinds = Array.isArray(targetVal.binds) ? targetVal.binds : [];
  for (let i = 0; i < rootBinds.length; i += 1) {
    addNamespace(["binds", i, "namespace"], rootBinds[i] && rootBinds[i].namespace, "bind");
  }

  // Capability binds
  const capabilityBinds = Array.isArray(capability.binds) ? capability.binds : [];
  for (let i = 0; i < capabilityBinds.length; i += 1) {
    addNamespace(
      ["capability", "binds", i, "namespace"],
      capabilityBinds[i] && capabilityBinds[i].namespace,
      "bind",
    );
  }

  return results;
};
