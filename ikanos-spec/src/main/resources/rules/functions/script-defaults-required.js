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

  // Collect all script steps from all step-bearing contexts
  const stepSources = [];

  // MCP tools
  for (let e = 0; e < exposes.length; e++) {
    const adapter = exposes[e];
    if (!adapter) continue;
    if (adapter.type === "mcp" && Array.isArray(adapter.tools)) {
      for (let t = 0; t < adapter.tools.length; t++) {
        const tool = adapter.tools[t];
        if (Array.isArray(tool.steps)) {
          for (let s = 0; s < tool.steps.length; s++) {
            stepSources.push({
              step: tool.steps[s],
              path: ["capability", "exposes", e, "tools", t, "steps", s],
            });
          }
        }
      }
    }
    // REST operations
    if (adapter.type === "rest" && Array.isArray(adapter.resources)) {
      for (let r = 0; r < adapter.resources.length; r++) {
        const resource = adapter.resources[r];
        if (Array.isArray(resource.operations)) {
          for (let o = 0; o < resource.operations.length; o++) {
            const op = resource.operations[o];
            if (Array.isArray(op.steps)) {
              for (let s = 0; s < op.steps.length; s++) {
                stepSources.push({
                  step: op.steps[s],
                  path: [
                    "capability", "exposes", e,
                    "resources", r, "operations", o, "steps", s,
                  ],
                });
              }
            }
          }
        }
      }
    }
  }

  // Aggregate functions
  const aggregates = Array.isArray(capability.aggregates)
    ? capability.aggregates
    : [];
  for (let a = 0; a < aggregates.length; a++) {
    const agg = aggregates[a];
    const functions = Array.isArray(agg.functions) ? agg.functions : [];
    for (let f = 0; f < functions.length; f++) {
      const fn = functions[f];
      if (Array.isArray(fn.steps)) {
        for (let s = 0; s < fn.steps.length; s++) {
          stepSources.push({
            step: fn.steps[s],
            path: ["capability", "aggregates", a, "functions", f, "steps", s],
          });
        }
      }
    }
  }

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
