/**
 * Polychro rule function: elicit-url
 *
 * Checks the `elicit` steps of a document, wherever they are declared (tools, resources,
 * operations, aggregate flows), as map or list entries of a `steps` block.
 *
 * Options:
 *   check: "https"         A URL mode step's `url` must start with `https://`. Plain `http://`
 *                          is accepted for a local host only, as the engine does (meant for development).
 *   check: "no-sensitive"  A URL mode step's `url` must not read the caller's claims
 *                          (`{{auth...}}`) nor a binding (`{{KEY}}`, `{{namespace.KEY}}`):
 *                          a URL is logged, shown and shared, so it must never carry
 *                          credentials, personal data or a pre-authenticated token.
 *   check: "form-secrets"  A form mode step should not ask for a field that looks like a
 *                          secret (password, API key, token, card number...): the MCP
 *                          specification forbids form mode for them, and URL mode is the
 *                          compliant way to collect them.
 *
 * Returns paths relative to the document root, the node the rule targets.
 */

const SECRET_FIELD = /(pass(word|wd|phrase)?|secret|api[-_]?key|access[-_]?key|private[-_]?key|token|credential|card[-_]?number|cvv|cvc|iban|ssn)$/i;
const LOCAL_HTTP = /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])([:/?#]|$)/i;
const VARIABLE = /\{\{\s*[#^/&{]?\s*([^\s}]+)\s*\}?\}\}/g;

function entries(collection) {
  if (Array.isArray(collection)) {
    return collection.map((value, index) => ({ value, locator: index }));
  }
  if (collection && typeof collection === "object") {
    return Object.keys(collection).map((key) => ({ value: collection[key], locator: key }));
  }
  return [];
}

/** Every elicit step of the document, with its path. */
function elicitSteps(node, path, found) {
  if (Array.isArray(node)) {
    node.forEach((item, index) => elicitSteps(item, path.concat(index), found));
    return found;
  }
  if (!node || typeof node !== "object") {
    return found;
  }
  for (const key of Object.keys(node)) {
    const value = node[key];
    if (key === "steps") {
      for (const entry of entries(value)) {
        if (entry.value && entry.value.type === "elicit") {
          found.push({ step: entry.value, path: path.concat("steps", entry.locator) });
        }
      }
    }
    elicitSteps(value, path.concat(key), found);
  }
  return found;
}

/** The binding keys and namespaces the document declares. */
function bindings(doc) {
  const names = new Set();
  const lists = [];
  if (doc && doc.capability && Array.isArray(doc.capability.binds)) {
    lists.push(doc.capability.binds);
  }
  if (doc && Array.isArray(doc.binds)) {
    lists.push(doc.binds);
  }
  for (const binds of lists) {
    for (const bind of binds) {
      if (!bind || typeof bind !== "object") continue;
      if (typeof bind.namespace === "string") names.add(bind.namespace);
      if (bind.keys && typeof bind.keys === "object") {
        Object.keys(bind.keys).forEach((key) => names.add(key));
      }
    }
  }
  return names;
}

export default function elicitUrl(targetVal, options) {
  if (!targetVal || typeof targetVal !== "object") return;
  const check = options && options.check;
  const results = [];
  const bound = check === "no-sensitive" ? bindings(targetVal) : null;

  for (const { step, path } of elicitSteps(targetVal, [], [])) {
    const name = path[path.length - 1];
    const urlMode = step.mode === "url";
    if (check === "https" && urlMode && typeof step.url === "string") {
      const url = step.url.trim();
      if (!/^https:\/\//i.test(url) && !LOCAL_HTTP.test(url)) {
        results.push({
          message: "Elicit step '" + name + "': the url must start with https://.",
          path: path.concat("url"),
        });
      }
    } else if (check === "no-sensitive" && urlMode && typeof step.url === "string") {
      for (const match of step.url.matchAll(VARIABLE)) {
        const variable = match[1];
        const head = variable.split(".")[0];
        if (head === "auth") {
          results.push({
            message: "Elicit step '" + name + "': the url reads the caller's claims ({{"
              + variable + "}}); never put personal data in a URL.",
            path: path.concat("url"),
          });
        } else if (bound.has(head)) {
          results.push({
            message: "Elicit step '" + name + "': the url reads the binding {{" + variable
              + "}}; never put a credential in a URL.",
            path: path.concat("url"),
          });
        }
      }
    } else if (check === "form-secrets" && !urlMode && step.fields
        && typeof step.fields === "object" && !Array.isArray(step.fields)) {
      for (const field of Object.keys(step.fields)) {
        if (SECRET_FIELD.test(field)) {
          results.push({
            message: "Elicit step '" + name + "': the field '" + field + "' looks like a "
              + "secret; form mode must not ask for one, use mode: url.",
            path: path.concat("fields", field),
          });
        }
      }
    }
  }
  return results;
}
