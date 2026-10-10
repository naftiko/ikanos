/**
 * Spectral custom function: mcp-ui-view-mime-type
 *
 * Enforces `ikanos-mcp-ui-resource-mime-type`: every exposed MCP resource whose
 * `uri` starts with `ui://` (an MCP Apps view, SEP-1865) must declare
 * `mimeType: text/html;profile=mcp-app`. Hosts ignore any other type, and the
 * engine refuses to start when a tool's `ui.resourceUri` resolves to one.
 *
 * Walks both `capability.exposes` and root-level `exposes` (shared section
 * documents). Resources are a keyed map (phase-2 shape); an array is also
 * accepted for robustness.
 */
const VIEW_MIME = /^text\/html\s*;\s*profile\s*=\s*"?mcp-app"?\s*$/i;

export default function mcpUiViewMimeType(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }

  const results = [];
  scanExposes(targetVal.exposes, ["exposes"], results);
  if (targetVal.capability && typeof targetVal.capability === "object") {
    scanExposes(targetVal.capability.exposes, ["capability", "exposes"], results);
  }
  return results;
}

function scanExposes(exposes, basePath, results) {
  if (!Array.isArray(exposes)) {
    return;
  }
  exposes.forEach((adapter, index) => {
    if (!adapter || adapter.type !== "mcp") {
      return;
    }
    for (const [key, resource] of entries(adapter.resources)) {
      if (!resource || typeof resource.uri !== "string" || !resource.uri.startsWith("ui://")) {
        continue;
      }
      const mimeType = resource.mimeType;
      if (typeof mimeType !== "string" || !VIEW_MIME.test(mimeType)) {
        results.push({
          message:
            "MCP Apps view '" + resource.uri + "' must declare mimeType 'text/html;profile=mcp-app'.",
          path: [...basePath, index, "resources", key, "mimeType"],
        });
      }
    }
  });
}

function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, i) => [i, value]);
  }
  if (collection && typeof collection === "object") {
    return Object.entries(collection);
  }
  return [];
}
