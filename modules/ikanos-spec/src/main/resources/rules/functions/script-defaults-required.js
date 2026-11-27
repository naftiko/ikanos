export default function scriptDefaultsRequired(targetVal) {
  if (!targetVal || typeof targetVal !== "object") return;

  const capability =
    targetVal.capability && typeof targetVal.capability === "object"
      ? targetVal.capability
      : {};

  // Check if a control adapter defines defaults
  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  let hasDefaultLocation = false;
  let hasDefaultLanguage = false;
  for (const adapter of exposes) {
    if (
      adapter &&
      adapter.type === "control" &&
      adapter.management &&
      adapter.management.scripting
    ) {
      const scripting = adapter.management.scripting;
      if (
        typeof scripting.defaultLocation === "string" &&
        scripting.defaultLocation.trim().length > 0
      ) {
        hasDefaultLocation = true;
      }
      if (
        typeof scripting.defaultLanguage === "string" &&
        scripting.defaultLanguage.trim().length > 0
      ) {
        hasDefaultLanguage = true;
      }
      break;
    }
  }

  // If both defaults are set, all steps are covered — nothing to check
  if (hasDefaultLocation && hasDefaultLanguage) return;

  const results = [];

  // Collect every step of the capability: the steps of MCP tools and resources,
  // REST operations and aggregate flows, and the steps nested in them (tracks of a
  // parallel step, steps of a sequence, the do of a forEach). Tools, resources,
  // operations and flows may be maps keyed by name or lists, so the document is
  // walked whole; a step is an object with a type in a steps or tracks list, or a do.
  const stepSources = [];
  function collect(node, path) {
    if (Array.isArray(node)) {
      node.forEach((item, index) => collect(item, path.concat(index)));
      return;
    }
    if (!node || typeof node !== "object") {
      return;
    }
    for (const key of Object.keys(node)) {
      const value = node[key];
      if ((key === "steps" || key === "tracks") && Array.isArray(value)) {
        value.forEach((step, index) => {
          if (step && typeof step === "object" && typeof step.type === "string") {
            stepSources.push({ step, path: path.concat(key, index) });
          }
        });
      } else if (key === "do" && value && typeof value === "object"
          && typeof value.type === "string") {
        stepSources.push({ step: value, path: path.concat(key) });
      }
      collect(value, path.concat(key));
    }
  }
  collect(capability, ["capability"]);

  // Report script steps that omit location or language without defaults
  for (const { step, path } of stepSources) {
    if (step && step.type === "script") {
      if (!step.location && !hasDefaultLocation) {
        results.push({
          message:
            "Script step '" +
            (step.name || "unnamed") +
            "' omits 'location' and no 'management.scripting.defaultLocation' " +
            "is configured. Add 'location' to this step or set a " +
            "'defaultLocation' on the control adapter.",
          path: [...path, "location"],
        });
      }
      if (!step.language && !hasDefaultLanguage) {
        results.push({
          message:
            "Script step '" +
            (step.name || "unnamed") +
            "' omits 'language' and no 'management.scripting.defaultLanguage' " +
            "is configured. Add 'language' to this step or set a " +
            "'defaultLanguage' on the control adapter.",
          path: [...path, "language"],
        });
      }
    }
  }

  return results;
}
