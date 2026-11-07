/**
 * Spectral custom function: capability-no-root-sections
 *
 * A capability document keeps its `consumes`, `exposes`, `aggregates` and `binds`
 * under `capability`. At the root, those sections are only valid in standalone
 * shared files (#955). The schema rejects them too, but its message does not say
 * where the section belongs; this rule does, so the migration is one step.
 */
const SECTIONS = ["consumes", "exposes", "aggregates", "binds"];

export default function capabilityNoRootSections(targetVal) {
  if (!targetVal || typeof targetVal !== "object" || !targetVal.capability) {
    return;
  }

  const results = [];
  for (const section of SECTIONS) {
    if (targetVal[section] !== undefined) {
      results.push({
        message:
          "Root '" + section + "' is not allowed in a capability document: move it under " +
          "'capability." + section + "'. Root '" + section +
          "' is only valid in a standalone shared file.",
        path: [section],
      });
    }
  }
  return results;
}
