/**
 * Spectral custom function: sql-procedures
 *
 * Checks every `consumes: type: sql` procedure (root `consumes` and
 * `capability.consumes`). `functionOptions.check` picks the rule:
 *
 * - `placeholders` (`ikanos-sql-placeholders-bound`): every `:placeholder` in a
 *   `statement` matches a declared input, through the input's `placeholder`
 *   or, without one, its key; every declared input is used by the statement.
 *   Placeholders are found the way the engine finds them: string literals,
 *   quoted identifiers, dollar-quoted strings, comments and `::` casts are
 *   skipped. As in the engine, the `connection.url` sets the dialect when it is
 *   a literal JDBC URL: block comments nest on PostgreSQL and SQL Server, and
 *   `[...]` is a quoted identifier on SQL Server and SQLite. On MySQL and
 *   MariaDB, `#` starts a comment, `--` does only before whitespace, a
 *   backslash escapes in quoted text, and there is no dollar quoting.
 * - `positions` (`ikanos-sql-stored-procedure-parameters`): every input of a
 *   `storedProcedure` declares a `position`; positions across inputs and
 *   `outParameters` are unique and contiguous from 1, an `INOUT` parameter
 *   taking the same position in both; `outParameters` only appear with a
 *   `storedProcedure`.
 */
export default function sqlProcedures(targetVal, opts) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  const check = opts && opts.check === "positions" ? checkPositions : checkPlaceholders;
  const results = [];
  scanConsumes(targetVal.consumes, ["consumes"], check, results);
  if (targetVal.capability && typeof targetVal.capability === "object") {
    scanConsumes(targetVal.capability.consumes, ["capability", "consumes"], check, results);
  }
  return results;
}

function scanConsumes(consumes, basePath, check, results) {
  if (!Array.isArray(consumes)) {
    return;
  }
  consumes.forEach((adapter, index) => {
    if (!adapter || adapter.type !== "sql" || !isObject(adapter.procedures)) {
      return;
    }
    for (const [name, procedure] of Object.entries(adapter.procedures)) {
      if (!isObject(procedure)) {
        continue;
      }
      const path = [...basePath, index, "procedures", name];
      check(adapter.namespace, name, procedure, path, dialectOf(adapter), results);
    }
  });
}

/** The lexical rules of the adapter's database, read from a literal JDBC URL. */
function dialectOf(adapter) {
  const url = isObject(adapter.connection) ? adapter.connection.url : undefined;
  const system = typeof url === "string" ? /^jdbc:([A-Za-z0-9]+):/i.exec(url.trim()) : null;
  const name = system ? system[1].toLowerCase() : "";
  return {
    nestedComments: name === "postgresql" || name === "sqlserver" || name === "jtds",
    brackets: name === "sqlserver" || name === "jtds" || name === "sqlite",
    mysql: name === "mysql" || name === "mariadb",
    sqlite: name === "sqlite",
  };
}

function checkPositions(namespace, name, procedure, path, dialect, results) {
  const where = "SQL procedure '" + namespace + "." + name + "'";
  const outs = isObject(procedure.outParameters) ? procedure.outParameters : {};
  if (typeof procedure.storedProcedure !== "string") {
    if (Object.keys(outs).length > 0) {
      results.push({
        message: where + " declares outParameters, which only a storedProcedure can return.",
        path: [...path, "outParameters"],
      });
    }
    return;
  }
  const inputs = isObject(procedure.inputParameters) ? procedure.inputParameters : {};
  const taken = new Map();
  const claim = (kind, key, position) => {
    const seen = taken.get(position) || {};
    if (seen[kind]) {
      results.push({
        message: where + " declares two " + kind + " parameters at position " + position + ".",
        path: [...path, kind === "input" ? "inputParameters" : "outParameters", key],
      });
    }
    seen[kind] = key;
    taken.set(position, seen);
  };
  for (const [key, input] of Object.entries(inputs)) {
    const position = isObject(input) ? input.position : undefined;
    if (!Number.isInteger(position) || position < 1) {
      results.push({
        message: where + " input '" + key + "' requires a position of 1 or more.",
        path: [...path, "inputParameters", key],
      });
      continue;
    }
    claim("input", key, position);
  }
  for (const [key, out] of Object.entries(outs)) {
    const position = isObject(out) ? out.position : undefined;
    if (Number.isInteger(position) && position >= 1) {
      claim("out", key, position);
    }
  }
  const highest = Math.max(0, ...taken.keys());
  for (let position = 1; position <= highest; position++) {
    if (!taken.has(position)) {
      results.push({
        message: where + " has no parameter at position " + position + "; positions must be contiguous from 1.",
        path: [...path, "storedProcedure"],
      });
    }
  }
}

function checkPlaceholders(namespace, name, procedure, path, dialect, results) {
  if (typeof procedure.statement !== "string") {
    return;
  }
  const used = placeholders(procedure.statement, dialect);
  const inputs = isObject(procedure.inputParameters) ? procedure.inputParameters : {};
  const byPlaceholder = new Map();
  for (const [key, input] of Object.entries(inputs)) {
    const placeholder =
      isObject(input) && typeof input.placeholder === "string" && input.placeholder !== ""
        ? input.placeholder
        : key;
    if (byPlaceholder.has(placeholder)) {
      results.push({
        message: "SQL procedure '" + namespace + "." + name + "' declares placeholder ':" + placeholder + "' on two inputs ('" + byPlaceholder.get(placeholder) + "' and '" + key + "').",
        path: [...path, "inputParameters", key],
      });
      continue;
    }
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
function placeholders(sql, dialect = {}) {
  const found = [];
  let i = 0;
  const n = sql.length;
  while (i < n) {
    const c = sql[i];
    if (startsLineComment(sql, i, dialect.mysql)) {
      const end = dialect.sqlite ? sql.indexOf("\n", i) : sql.slice(i).search(/[\r\n]/);
      i = end < 0 ? n : dialect.sqlite ? end : i + end;
    } else if (c === "/" && sql[i + 1] === "*") {
      i = endOfBlockComment(sql, i, dialect.nestedComments);
    } else if (c === "[" && dialect.brackets) {
      i = endOfQuoted(sql, i, "]", false);
    } else if (c === "'" || c === '"' || c === "`") {
      const backslash = dialect.mysql
        ? c !== "`"
        : c === "'" && i > 0 && /[Ee]/.test(sql[i - 1]) && !/\w/.test(sql[i - 2] || "");
      i = endOfQuoted(sql, i, c, backslash);
    } else if (!dialect.mysql && c === "$" && !/\w/.test(sql[i - 1] || "") && !/\d/.test(sql[i + 1] || "")) {
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

/** `--`, and on MySQL `#`; MySQL needs whitespace after `--`, as the engine reads it. */
function startsLineComment(sql, i, mysql) {
  if (mysql && sql[i] === "#") {
    return true;
  }
  if (sql[i] !== "-" || sql[i + 1] !== "-") {
    return false;
  }
  return !mysql || i + 2 >= sql.length || /\s/.test(sql[i + 2]);
}

/** The index just past the comment at `start`; nested comments count when the dialect nests them. */
function endOfBlockComment(sql, start, nested) {
  let depth = 0;
  let i = start;
  while (i + 1 < sql.length) {
    if (sql[i] === "/" && sql[i + 1] === "*" && (nested || depth === 0)) {
      depth++;
      i += 2;
    } else if (sql[i] === "*" && sql[i + 1] === "/") {
      depth--;
      i += 2;
      if (depth === 0) {
        return i;
      }
    } else {
      i++;
    }
  }
  return sql.length;
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
