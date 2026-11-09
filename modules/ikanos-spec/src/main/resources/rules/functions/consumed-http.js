/**
 * Checks on consumed HTTP operations that the schema cannot express.
 *
 * Options:
 *   check: "idempotency-key-safe-method" — an operation with a safe method declares
 *          `idempotencyKey`: the upstream has no use for the header.
 *   check: "pagination-style-shape" — the resolved `pagination` of an operation has a `style`
 *          without its sub-block, or no `items`.
 *   check: "pagination-unsafe-method" — the resolved `pagination` of an operation has a
 *          `style` and the operation is not a GET.
 *   check: "pagination-page-size-required" — `style: page` or `offset` without `pageSize`.
 *   check: "pagination-large-bound-under-none" — `maxPages` above 100 under the `none`
 *          durability mode, where the drain must fit one call's time budget.
 *
 * A `pagination` block resolves field by field: the operation's over its resource's over its
 * adapter's, each sub-block taken whole from the narrowest level that declares it.
 *
 * Both root-level `consumes` (shared section documents) and `capability.consumes` are checked.
 * Result paths are relative to the document root, which the rules target.
 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const STYLE_BLOCKS = {
  cursor: { block: "cursor", needs: ["from"] },
  nextLink: { block: "nextLink", needs: ["from"] },
  page: { block: "page", needs: ["param"] },
  offset: { block: "offset", needs: ["param"] },
};
const PAGINATION_FIELDS = ["style", "items", "cursor", "nextLink", "page", "offset", "pageSize",
  "maxPages", "maxItems"];

export default function consumedHttp(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = options && options.check;
  const results = [];
  const mode = durabilityMode(targetVal);
  for (const op of operations(targetVal)) {
    if (check === "idempotency-key-safe-method") {
      checkIdempotencyKey(op, results);
      continue;
    }
    const pagination = resolvePagination(op);
    if (!pagination || typeof pagination.style !== "string") {
      continue;
    }
    if (check === "pagination-style-shape") {
      checkStyleShape(op, pagination, results);
    } else if (check === "pagination-unsafe-method" && op.method !== "GET") {
      results.push({
        message: "Operation '" + op.label + "' is a " + op.method + " and pages: only a GET may "
          + "be repeated once per page.",
        path: op.path.concat("method"),
      });
    } else if (check === "pagination-page-size-required"
        && (pagination.style === "page" || pagination.style === "offset")
        && !(pagination.pageSize && typeof pagination.pageSize.param === "string"
          && Number.isInteger(pagination.pageSize.value))) {
      results.push({
        message: "Operation '" + op.label + "' pages by " + pagination.style + " without a "
          + "`pageSize`: a page that is not full is how its last page is told.",
        path: op.path.concat("pagination"),
      });
    } else if (check === "pagination-large-bound-under-none" && mode === "none"
        && Number.isInteger(pagination.maxPages) && pagination.maxPages > 100) {
      results.push({
        message: "Operation '" + op.label + "' may fetch " + pagination.maxPages + " pages under "
          + "the `none` durability mode, inside one call's time budget.",
        path: op.path.concat("pagination"),
      });
    }
  }
  return results;
}

function checkStyleShape(op, pagination, results) {
  const shape = STYLE_BLOCKS[pagination.style];
  if (!shape) {
    return; // the schema reports an unknown style
  }
  const block = pagination[shape.block];
  const missing = [];
  if (typeof pagination.items !== "string" || pagination.items.trim() === "") {
    missing.push("`items`");
  }
  if (!block || typeof block !== "object") {
    missing.push("`" + shape.block + "`");
  } else {
    for (const field of shape.needs) {
      if (block[field] === undefined || block[field] === null) {
        missing.push("`" + shape.block + "." + field + "`");
      }
    }
    if (pagination.style === "cursor" && typeof block.param !== "string"
        && typeof block.header !== "string") {
      missing.push("`cursor.param` or `cursor.header`");
    }
  }
  if (missing.length > 0) {
    results.push({
      message: "Operation '" + op.label + "' pages by " + pagination.style + " without "
        + missing.join(", ") + ".",
      path: op.path.concat("pagination"),
    });
  }
}

/** The operation's pagination, resolved over its resource's and adapter's, or null. */
function resolvePagination(op) {
  let resolved = null;
  for (const level of [op.adapter.pagination, op.resource && op.resource.pagination,
    op.value.pagination]) {
    if (!level || typeof level !== "object") {
      continue;
    }
    resolved = resolved || {};
    for (const field of PAGINATION_FIELDS) {
      if (level[field] !== undefined && level[field] !== null) {
        resolved[field] = level[field];
      }
    }
  }
  return resolved;
}

/** The durability mode of the capability's control port: `none` when it declares none. */
function durabilityMode(doc) {
  const capability = doc.capability && typeof doc.capability === "object" ? doc.capability : {};
  for (const e of entries(capability.exposes)) {
    const adapter = e.value;
    if (adapter && adapter.type === "control" && adapter.durability
        && typeof adapter.durability.mode === "string") {
      return adapter.durability.mode;
    }
  }
  return "none";
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
