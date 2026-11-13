/**
 * Spectral custom function: sql-procedures
 *
 * Enforces `ikanos-sql-placeholders-bound` on every `consumes: type: sql`
 * procedure (root `consumes` and `capability.consumes`):
 *
 * - every `:placeholder` in `statement` matches a declared input, through the
 *   input's `placeholder` or, without one, its key;
 * - every declared input is used by the statement.
 *
 * Placeholders are found the way the engine finds them: string literals,
 * quoted identifiers, dollar-quoted strings, comments and `::` casts are
 * skipped.
 */
export default function sqlProcedures(targetVal) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const results = [];
  scanConsumes(targetVal.consumes, ["consumes"], results);
  if (targetVal.capability && typeof targetVal.capability === "object") {
    scanConsumes(targetVal.capability.consumes, ["capability", "consumes"], results);
  }
  return results;
}

function scanConsumes(consumes, basePath, results) {
  if (!Array.isArray(consumes)) {
    return;
  }
  consumes.forEach((adapter, index) => {
    if (!adapter || adapter.type !== "sql" || !isObject(adapter.procedures)) {
      return;
    }
    for (const [name, procedure] of Object.entries(adapter.procedures)) {
      if (!isObject(procedure) || typeof procedure.statement !== "string") {
        continue;
      }
      const path = [...basePath, index, "procedures", name];
      checkPlaceholders(adapter.namespace, name, procedure, path, results);
    }
  });
}

function checkPlaceholders(namespace, name, procedure, path, results) {
  const used = placeholders(procedure.statement);
  const inputs = isObject(procedure.inputParameters) ? procedure.inputParameters : {};
  const byPlaceholder = new Map();
  for (const [key, input] of Object.entries(inputs)) {
    const placeholder =
      isObject(input) && typeof input.placeholder === "string" && input.placeholder !== ""
        ? input.placeholder
        : key;
    byPlaceholder.set(placeholder, key);
  }
  const where = "SQL procedure '" + namespace + "." + name + "'";
  for (const placeholder of new Set(used)) {
    if (!byPlaceholder.has(placeholder)) {
      results.push({
        message: where + " uses placeholder ':" + placeholder + "', which matches no declared input.",
        path: [...path, "statement"],
      });
    }
  }
  for (const [placeholder, key] of byPlaceholder) {
    if (!used.includes(placeholder)) {
      results.push({
        message: where + " declares input '" + key + "', which its statement never uses (:" + placeholder + ").",
        path: [...path, "inputParameters", key],
      });
    }
  }
}

/** The `:name` placeholders of a statement, in order, outside literals and comments. */
function placeholders(sql) {
  const found = [];
  let i = 0;
  const n = sql.length;
  while (i < n) {
    const c = sql[i];
    if (c === "-" && sql[i + 1] === "-") {
      const end = sql.indexOf("\n", i);
      i = end < 0 ? n : end;
    } else if (c === "/" && sql[i + 1] === "*") {
      const end = sql.indexOf("*/", i + 2);
      i = end < 0 ? n : end + 2;
    } else if (c === "'" || c === '"' || c === "`") {
      const backslash = c === "'" && i > 0 && /[Ee]/.test(sql[i - 1]) && !/\w/.test(sql[i - 2] || "");
      i = endOfQuoted(sql, i, c, backslash);
    } else if (c === "$" && !/\w/.test(sql[i - 1] || "") && !/\d/.test(sql[i + 1] || "")) {
      const tag = /^\$\w*\$/.exec(sql.slice(i));
      if (tag) {
        const end = sql.indexOf(tag[0], i + tag[0].length);
        i = end < 0 ? n : end + tag[0].length;
      } else {
        i++;
      }
    } else if (c === ":" && sql[i + 1] === ":") {
      i += 2;
    } else if (c === ":" && /[A-Za-z_]/.test(sql[i + 1] || "")) {
      const match = /^[A-Za-z_]\w*/.exec(sql.slice(i + 1));
      found.push(match[0]);
      i += 1 + match[0].length;
    } else {
      i++;
    }
  }
  return found;
}

function endOfQuoted(sql, start, quote, backslash) {
  let i = start + 1;
  while (i < sql.length) {
    if (backslash && sql[i] === "\\") {
      i += 2;
      continue;
    }
    if (sql[i] === quote) {
      if (sql[i + 1] === quote) {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return sql.length;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
