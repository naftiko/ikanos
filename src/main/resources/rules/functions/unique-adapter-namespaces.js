module.exports = function uniqueAdapterNamespaces(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const seen = new Map();
  const results = [];

  function addNamespace(path, value) {
    if (typeof value !== "string" || value.length === 0) {
      return;
    }

    const prior = seen.get(value);
    if (prior === undefined) {
      seen.set(value, path);
      return;
    }

    results.push({
      message:
        "Namespace '" +
        value +
        "' is already used at " +
        prior.join("/") +
        ". Namespaces must be globally unique across consumes and exposes.",
      path,
    });
  }

  const rootConsumes = Array.isArray(targetVal.consumes) ? targetVal.consumes : [];
  for (let i = 0; i < rootConsumes.length; i += 1) {
    addNamespace(["consumes", i, "namespace"], rootConsumes[i] && rootConsumes[i].namespace);
  }

  const capability = targetVal.capability && typeof targetVal.capability === "object" ? targetVal.capability : {};

  const capabilityConsumes = Array.isArray(capability.consumes) ? capability.consumes : [];
  for (let i = 0; i < capabilityConsumes.length; i += 1) {
    addNamespace(
      ["capability", "consumes", i, "namespace"],
      capabilityConsumes[i] && capabilityConsumes[i].namespace,
    );
  }

  const capabilityExposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  for (let i = 0; i < capabilityExposes.length; i += 1) {
    addNamespace(
      ["capability", "exposes", i, "namespace"],
      capabilityExposes[i] && capabilityExposes[i].namespace,
    );
  }

  return results;
};
