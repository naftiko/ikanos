/**
 * Polychro rule function: mcp-tool-description
 *
 * Checks the descriptions of the tools of exposed MCP adapters, which tool search
 * (and the model, in listed mode) relies on to tell tools apart. Select the check
 * with `functionOptions.check`:
 *
 *   - "missing":         a listed tool has a blank description. A tool with `ref`
 *                        and no description (or an empty one) inherits that of its
 *                        aggregate flow, as in the engine; it is reported only when
 *                        that flow is in the document and has none either. A tool
 *                        with neither `description` nor `ref` is left to the schema,
 *                        which rejects it. Tools named in `toolSearch.alwaysListed`
 *                        are listed even in deferred mode;
 *   - "deferredMissing": the same, on a tool that deferred tool search hides, which
 *                        search can only find by its name without a description;
 *   - "restatesName":    once the words of the tool name and common filler words
 *                        are removed, fewer than MIN_EXTRA_WORDS words are left in
 *                        the description ("sync_application syncs application");
 *   - "similar":         two tools of the same adapter have descriptions whose
 *                        word sets overlap by SIMILARITY_THRESHOLD or more
 *                        (Jaccard index), so the model can swap them.
 *
 * Words are split the way the tool search index splits them: on spaces,
 * punctuation, hyphens, underscores and case changes, then lower-cased and
 * reduced to a rough stem, so `restart-deployment`, `restartDeployment` and
 * "restarts the deployment" give the same words.
 *
 * Walks MCP adapters in `capability.exposes` and in root-level `exposes`, and
 * resolves `ref` against `capability.aggregates` and root-level `aggregates`.
 */
const MIN_EXTRA_WORDS = 2;
const SIMILARITY_THRESHOLD = 0.8;
const MIN_SIMILAR_WORDS = 3;

// Words that say nothing about what a tool does, in a tool description. They are compared
// with stemmed words, so they are stemmed too.
const STOP_WORDS = new Set([
  "a", "an", "and", "any", "are", "as", "at", "be", "by", "can", "do", "does", "each",
  "for", "from", "given", "has", "have", "if", "in", "into", "is", "it", "its", "of",
  "on", "one", "or", "provided", "provides", "specified", "specifies", "that", "the",
  "their", "them", "this", "to", "tool", "use", "used", "uses", "using", "via", "when",
  "which", "will", "with",
].map(stem));

export default function mcpToolDescription(targetVal, options) {
  if (!isObject(targetVal)) {
    return;
  }
  const check = options && options.check;
  const flows = new Map();
  indexFlows(targetVal.aggregates, flows);
  if (isObject(targetVal.capability)) {
    indexFlows(targetVal.capability.aggregates, flows);
  }
  const results = [];
  scan(targetVal.exposes, ["exposes"], check, flows, results);
  if (isObject(targetVal.capability)) {
    scan(targetVal.capability.exposes, ["capability", "exposes"], check, flows, results);
  }
  return results;
}

function scan(exposes, basePath, check, flows, results) {
  if (!Array.isArray(exposes)) {
    return;
  }
  exposes.forEach((adapter, index) => {
    if (!isObject(adapter) || adapter.type !== "mcp" || !isObject(adapter.tools)) {
      return;
    }
    const path = basePath.concat(index, "tools");
    const search = isObject(adapter.toolSearch) ? adapter.toolSearch : null;
    const pinned = search !== null && Array.isArray(search.alwaysListed) ? search.alwaysListed : [];
    const described = [];
    for (const [name, tool] of Object.entries(adapter.tools)) {
      if (!isObject(tool) || (tool.description === undefined && tool.ref === undefined)) {
        // The schema requires a description on every tool without `ref`.
        continue;
      }
      // A pinned tool is listed even in deferred mode.
      const deferred = search !== null && search.mode === "deferred" && !pinned.includes(name);
      // Like the engine (AggregateRefResolver), a `ref` tool inherits its flow's description
      // only when its own is absent or empty: a whitespace-only one is kept, and is blank.
      const declared = typeof tool.description === "string" && tool.description !== "";
      const own = declared ? text(tool.description) : null;
      const description = declared ? own : inherited(tool, flows);
      if (description === undefined) {
        // `ref` to a flow this document does not hold (an import): nothing to check.
        continue;
      }
      if (description === null) {
        if ((check === "missing" && !deferred) || (check === "deferredMissing" && deferred)) {
          results.push({
            message: "Tool '" + name + "' has no description"
              + (!declared && typeof tool.ref === "string"
                ? ", and its flow '" + tool.ref + "' has none" : "")
              + (deferred
                ? ": in deferred mode, search can only find it by its name."
                : ": the model can only tell what it does from its name.")
              + " Say what it does, on what, and what it returns.",
            path: path.concat(name),
          });
        }
        continue;
      }
      // Inherited descriptions are checked on the flow, where the author can fix them.
      const descriptionPath = own !== null ? path.concat(name, "description") : path.concat(name);
      const words = informativeWords(description);
      if (check === "restatesName") {
        const nameWords = new Set(wordsOf(name));
        const extra = new Set([...words].filter((word) => !nameWords.has(word)));
        if (own !== null && extra.size < MIN_EXTRA_WORDS) {
          results.push({
            message: "The description of tool '" + name + "' mostly repeats its name. Say"
              + " what it acts on, when to use it, or what it returns.",
            path: descriptionPath,
          });
        }
      }
      described.push({ name, words, path: descriptionPath });
    }
    if (check === "similar") {
      reportSimilar(described, results);
    }
  });
}

/** Reports the second tool of each pair whose descriptions are near duplicates. */
function reportSimilar(described, results) {
  for (let i = 1; i < described.length; i++) {
    for (let j = 0; j < i; j++) {
      const a = described[j];
      const b = described[i];
      const union = new Set([...a.words, ...b.words]);
      if (union.size < MIN_SIMILAR_WORDS) {
        continue;
      }
      const shared = [...a.words].filter((word) => b.words.has(word)).length;
      if (shared / union.size >= SIMILARITY_THRESHOLD) {
        results.push({
          message: "Tools '" + a.name + "' and '" + b.name + "' have nearly the same"
            + " description, so the model can pick one for the other. Say what sets '"
            + b.name + "' apart.",
          path: b.path,
        });
        break;
      }
    }
  }
}

/**
 * The description a `ref` tool inherits: the flow's text, null when the flow has none,
 * undefined when the flow is not in this document.
 */
function inherited(tool, flows) {
  if (typeof tool.ref !== "string") {
    return null;
  }
  return flows.has(tool.ref) ? flows.get(tool.ref) : undefined;
}

/** Maps "namespace.flow" to the flow's description text (null when it has none). */
function indexFlows(aggregates, flows) {
  for (const aggregate of values(aggregates)) {
    if (!isObject(aggregate) || typeof aggregate.namespace !== "string") {
      continue;
    }
    const entries = Array.isArray(aggregate.flows)
      ? aggregate.flows.filter(isObject).map((flow) => [flow.name, flow])
      : Object.entries(isObject(aggregate.flows) ? aggregate.flows : {});
    for (const [name, flow] of entries) {
      if (typeof name === "string" && isObject(flow)) {
        flows.set(aggregate.namespace + "." + name, text(flow.description));
      }
    }
  }
}

/** The stemmed words of a description, without filler words. */
function informativeWords(description) {
  return new Set(wordsOf(description).filter((word) => !STOP_WORDS.has(word)));
}

/**
 * Splits text into lower-cased, roughly stemmed words: on anything but letters and
 * digits of any script, and on case changes (`restartDeployment` gives `restart`,
 * `deployment`).
 */
function wordsOf(value) {
  return value
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((word) => word.length > 0)
    .map(stem);
}

/**
 * A rough English stem, enough to match "syncs" with "sync" and "updated" with "update".
 * It only needs to agree with itself, not with the search index's Porter stemmer.
 */
function stem(word) {
  let result = word;
  for (const suffix of ["ies", "ing", "ed", "es", "s"]) {
    if (result.endsWith(suffix) && result.length - suffix.length >= 3) {
      result = result.slice(0, -suffix.length) + (suffix === "ies" ? "y" : "");
      break;
    }
  }
  if (result.endsWith("e") && result.length > 3) {
    result = result.slice(0, -1);
  }
  return result;
}

function text(value) {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function values(collection) {
  if (Array.isArray(collection)) {
    return collection;
  }
  return isObject(collection) ? Object.values(collection) : [];
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
