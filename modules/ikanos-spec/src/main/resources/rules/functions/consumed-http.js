/**
 * Checks on consumed HTTP operations that the schema cannot express.
 *
 * Options:
 *   check: "idempotency-key-safe-method" — an operation with a safe method declares
 *          `idempotencyKey`: the upstream has no use for the header.
 *
 * Both root-level `consumes` (shared section documents) and `capability.consumes` are checked.
 * Result paths are relative to the document root, which the rules target.
 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export default function consumedHttp(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = options && options.check;
  const results = [];
  for (const op of operations(targetVal)) {
    if (check === "idempotency-key-safe-method") {
      checkIdempotencyKey(op, results);
    }
  }
  return results;
}

function checkIdempotencyKey(op, results) {
  const declared = op.value.idempotencyKey;
  const sends = declared === true || (typeof declared === "string" && declared.trim() !== "");
  if (sends && SAFE_METHODS.has(op.method)) {
    results.push({
      message: "Operation '" + op.label + "' is a " + op.method
        + ": it declares `idempotencyKey`, which only a write needs.",
      path: op.path.concat("idempotencyKey"),
    });
  }
}

/** Every consumed HTTP operation, with its resolved method, a label and its path. */
function operations(doc) {
  const found = [];
  const roots = [
    { list: doc.consumes, path: ["consumes"] },
    {
      list: doc.capability && typeof doc.capability === "object" ? doc.capability.consumes : null,
      path: ["capability", "consumes"],
    },
  ];
  for (const root of roots) {
    for (const c of entries(root.list)) {
      const consumed = c.value;
      if (!consumed || consumed.type !== "http" || typeof consumed.namespace !== "string") {
        continue;
      }
      for (const r of entries(consumed.resources)) {
        for (const o of entries(r.value && r.value.operations)) {
          if (!o.value || typeof o.value !== "object") {
            continue;
          }
          const method = typeof o.value.method === "string" ? o.value.method.toUpperCase() : "GET";
          found.push({
            adapter: consumed,
            resource: r.value,
            value: o.value,
            method,
            label: consumed.namespace + "." + o.key,
            path: root.path.concat(...c.locator, "resources", ...r.locator, "operations",
              ...o.locator),
          });
        }
      }
    }
  }
  return found;
}

function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, index) => ({
      key: value && typeof value.name === "string" ? value.name : String(index),
      value,
      locator: [index],
    }));
  }
  if (collection && typeof collection === "object") {
    return Object.keys(collection).map((key) => ({
      key,
      value: collection[key],
      locator: [key],
    }));
  }
  return [];
}
