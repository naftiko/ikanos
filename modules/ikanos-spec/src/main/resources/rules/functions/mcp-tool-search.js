/**
 * Spectral custom function: mcp-tool-search
 *
 * Checks the `toolSearch` block of MCP adapters against their tools. Select the
 * check with `functionOptions.check`:
 *
 *   - "reservedNames":   a tool uses the name of a meta-tool of a declared
 *                        toolSearch block (the engine refuses to load);
 *   - "implicitReservedNames": a tool uses a default meta-tool name on an
 *                        adapter without a toolSearch block (the engine then
 *                        disables tool search for clients);
 *   - "lookalikeNames":  a tool is named `search_tools` or `call_tool`, which
 *                        models confuse with the meta-tools of deferred mode;
 *   - "alwaysListedExists": an `alwaysListed` entry names no tool;
 *   - "alwaysListedSize": more than five tools in `alwaysListed`, which leaves
 *                        little to defer;
 *   - "description":     deferred mode without a server `description`, so the
 *                        search meta-tool cannot say what the endpoint covers;
 *   - "smallCatalog":    deferred mode on fewer than ten tools, where a search
 *                        round trip costs more than the definitions it saves.
 *
 * Walks MCP adapters in `capability.exposes` and in root-level `exposes`.
 */
const DEFAULT_SEARCH = "search-tools";
const DEFAULT_CALL = "call-tool";
const LOOKALIKES = ["search_tools", "call_tool"];
const MAX_ALWAYS_LISTED = 5;
const MIN_DEFERRED_TOOLS = 10;

export default function mcpToolSearch(targetVal, options) {
  if (!isObject(targetVal)) {
    return;
  }
  const check = options && options.check;
  const results = [];
  scan(targetVal.exposes, ["exposes"], check, results);
  if (isObject(targetVal.capability)) {
    scan(targetVal.capability.exposes, ["capability", "exposes"], check, results);
  }
  return results;
}

function scan(exposes, basePath, check, results) {
  if (!Array.isArray(exposes)) {
    return;
  }
  exposes.forEach((adapter, index) => {
    if (!isObject(adapter) || adapter.type !== "mcp") {
      return;
    }
    const path = basePath.concat(index);
    const tools = isObject(adapter.tools) ? Object.keys(adapter.tools) : [];
    const block = isObject(adapter.toolSearch) ? adapter.toolSearch : null;
    const searchName = block && block.searchToolName ? block.searchToolName : DEFAULT_SEARCH;
    const callName = block && block.callToolName ? block.callToolName : DEFAULT_CALL;
    const deferred = block !== null && block.mode === "deferred";

    if (check === "reservedNames" && block !== null) {
      for (const name of [searchName, callName]) {
        if (tools.includes(name)) {
          results.push({
            message: "Tool '" + name + "' uses a meta-tool name of toolSearch: rename the tool,"
              + " or set toolSearch.searchToolName/callToolName.",
            path: path.concat("tools", name),
          });
        }
      }
    } else if (check === "implicitReservedNames" && block === null) {
      for (const name of [DEFAULT_SEARCH, DEFAULT_CALL]) {
        if (tools.includes(name)) {
          results.push({
            message: "Tool '" + name + "' uses a default meta-tool name, so clients cannot"
              + " select deferred tool search on this endpoint. Rename the tool, or declare"
              + " toolSearch with other searchToolName/callToolName.",
            path: path.concat("tools", name),
          });
        }
      }
    } else if (check === "lookalikeNames") {
      for (const name of LOOKALIKES) {
        if (tools.includes(name) && name !== searchName && name !== callName) {
          results.push({
            message: "Tool '" + name + "' looks like a meta-tool of deferred mode ('"
              + searchName + "', '" + callName + "'); models may confuse them. Choose a name"
              + " that says what the tool does.",
            path: path.concat("tools", name),
          });
        }
      }
    } else if (check === "alwaysListedExists" && block !== null
        && Array.isArray(block.alwaysListed)) {
      block.alwaysListed.forEach((name, i) => {
        if (!tools.includes(name)) {
          results.push({
            message: "toolSearch.alwaysListed names '" + name + "', which is not a tool of this"
              + " endpoint.",
            path: path.concat("toolSearch", "alwaysListed", i),
          });
        }
      });
    } else if (check === "alwaysListedSize" && block !== null
        && Array.isArray(block.alwaysListed) && block.alwaysListed.length > MAX_ALWAYS_LISTED) {
      results.push({
        message: "toolSearch.alwaysListed pins " + block.alwaysListed.length + " tools: keep it"
          + " to the " + MAX_ALWAYS_LISTED + " or fewer that most calls need, so deferral still"
          + " saves tokens.",
        path: path.concat("toolSearch", "alwaysListed"),
      });
    } else if (check === "description" && deferred
        && (typeof adapter.description !== "string" || adapter.description.trim() === "")) {
      results.push({
        message: "Deferred tool search without a server description: the search meta-tool"
          + " cannot tell the model what this endpoint covers. Add a `description`.",
        path: path.concat("toolSearch", "mode"),
      });
    } else if (check === "smallCatalog" && deferred && tools.length < MIN_DEFERRED_TOOLS) {
      results.push({
        message: "Deferred tool search on " + tools.length + " tools: below "
          + MIN_DEFERRED_TOOLS + " tools, a search round trip usually costs more than the"
          + " definitions it saves. Prefer mode: listed.",
        path: path.concat("toolSearch", "mode"),
      });
    }
  });
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
