export default function controlPortValidation(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};

  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];

  const results = [];
  const controlAdapters = [];
  const businessPorts = [];

  for (let i = 0; i < exposes.length; i += 1) {
    const adapter = exposes[i];
    if (!adapter) continue;

    if (adapter.type === "control") {
      controlAdapters.push({ index: i, port: adapter.port });
    } else if (adapter.port) {
      businessPorts.push({ index: i, type: adapter.type, port: adapter.port });
    }
  }

  // Singleton check: at most one control adapter
  if (controlAdapters.length > 1) {
    for (let i = 1; i < controlAdapters.length; i += 1) {
      results.push({
        message:
          "Only one control adapter is allowed per capability. " +
          "Found duplicate at exposes[" +
          controlAdapters[i].index +
          "].",
        path: ["capability", "exposes", controlAdapters[i].index, "type"],
      });
    }
  }

  // Port uniqueness: control port must not conflict with business adapter ports
  for (const ctrl of controlAdapters) {
    for (const biz of businessPorts) {
      if (ctrl.port === biz.port) {
        results.push({
          message:
            "Control port " +
            ctrl.port +
            " conflicts with " +
            biz.type +
            " adapter at exposes[" +
            biz.index +
            "]. Use a dedicated port for the control adapter.",
          path: ["capability", "exposes", ctrl.index, "port"],
        });
      }
    }
  }

  return results;
}
