/**
 * Spectral custom function: sql-procedures
 *
 * Checks every `consumes: type: sql` procedure (root `consumes` and
 * `capability.consumes`). `functionOptions.check` picks the rule:
 *
 * - `placeholders` (`ikanos-sql-placeholders-bound`): every `{{name}}`
 *   placeholder in a `statement` names a declared input key, and every
 *   declared input is used by the statement. No placeholder sits inside a
 *   string literal or quoted identifier, where it would be sent as text instead
 *   of bound; no `{{…}}` tag is anything but a plain input name; and no JDBC
 *   syntax appears (an escape such as `{fn ...}`, a `?` marker outside
 *   PostgreSQL, an Oracle `:name` marker). Placeholders are found the way the
 *   engine finds them: string literals, quoted identifiers, dollar-quoted
 *   strings and comments are skipped. As in the engine, the `connection.url`
 *   sets the dialect when it is a literal JDBC URL: block comments nest on
 *   PostgreSQL and SQL Server, and `[...]` is a quoted identifier on SQL Server
 *   and SQLite. On MySQL and MariaDB, `#` starts a comment, `--` does only
 *   before whitespace, a backslash escapes in quoted text, and there is no
 *   dollar quoting.
 * - `positions` (`ikanos-sql-stored-procedure-parameters`): every input of a
 *   `storedProcedure` declares a `position`; positions across inputs and
 *   `outParameters` are unique and contiguous from 1, an `INOUT` parameter
 *   taking the same position in both; `outParameters` only appear with a
 *   `storedProcedure`.
 * - `exposure` (`ikanos-sql-write-exposure`): a procedure with
 *   `readOnly: false` is not called by an MCP tool claiming
 *   `hints.readOnly: true`, a REST GET or HEAD operation, or an aggregate flow
 *   declaring `semantics.safe: true`.
 */
export default function sqlProcedures(targetVal, opts) {
  if (!targetVal || typeof targetVal !== "object") {
    return;
  }
  if (opts && opts.check === "exposure") {
    return checkExposure(targetVal);
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
    known: name !== "",
    postgresql: name === "postgresql",
    nestedComments: name === "postgresql" || name === "sqlserver" || name === "jtds",
    brackets: name === "sqlserver" || name === "jtds" || name === "sqlite",
    mysql: name === "mysql" || name === "mariadb",
    sqlite: name === "sqlite",
    oracle: name === "oracle",
  };
}

function writeProcedures(targetVal) {
  const writes = new Set();
  const collect = (consumes) => {
    if (!Array.isArray(consumes)) {
      return;
    }
    for (const adapter of consumes) {
      if (!adapter || adapter.type !== "sql" || !isObject(adapter.procedures)) {
        continue;
      }
      for (const [name, procedure] of Object.entries(adapter.procedures)) {
        if (isObject(procedure) && procedure.readOnly === false) {
          writes.add(adapter.namespace + "." + name);
        }
      }
    }
  };
  collect(targetVal.consumes);
  if (isObject(targetVal.capability)) {
    collect(targetVal.capability.consumes);
  }
  return writes;
}

function checkExposure(targetVal) {
  const writes = writeProcedures(targetVal);
  const results = [];
  if (writes.size === 0 || !isObject(targetVal.capability)) {
    return results;
  }
  const capability = targetVal.capability;
  const exposes = Array.isArray(capability.exposes) ? capability.exposes : [];
  exposes.forEach((adapter, index) => {
    if (!isObject(adapter)) {
      return;
    }
    const base = ["capability", "exposes", index];
    if (adapter.type === "mcp" && isObject(adapter.tools)) {
      for (const [name, tool] of Object.entries(adapter.tools)) {
        if (isObject(tool) && writes.has(tool.call) && isObject(tool.hints)
            && tool.hints.readOnly === true) {
          results.push({
            message: "MCP tool '" + name + "' claims hints.readOnly: true but calls SQL write procedure '" + tool.call + "'.",
            path: [...base, "tools", name, "hints", "readOnly"],
          });
        }
      }
    }
    if (adapter.type === "rest" && isObject(adapter.resources)) {
      for (const [resourceName, resource] of Object.entries(adapter.resources)) {
        const operations = isObject(resource) && isObject(resource.operations) ? resource.operations : {};
        for (const [name, operation] of Object.entries(operations)) {
          const method = isObject(operation) && typeof operation.method === "string"
            ? operation.method.toUpperCase() : "";
          if (writes.has(operation.call) && (method === "GET" || method === "HEAD")) {
            results.push({
              message: "REST operation '" + name + "' uses " + method + " but calls SQL write procedure '" + operation.call + "'.",
              path: [...base, "resources", resourceName, "operations", name, "method"],
            });
          }
        }
      }
    }
  });
  const aggregates = Array.isArray(capability.aggregates) ? capability.aggregates : [];
  aggregates.forEach((aggregate, index) => {
    const flows = isObject(aggregate) && isObject(aggregate.flows) ? aggregate.flows : {};
    for (const [name, flow] of Object.entries(flows)) {
      if (!isObject(flow) || !isObject(flow.semantics) || flow.semantics.safe !== true) {
        continue;
      }
      const calls = [flow.call];
      if (isObject(flow.steps)) {
        for (const step of Object.values(flow.steps)) {
          if (isObject(step)) {
            calls.push(step.call);
          }
        }
      }
      const write = calls.find((call) => writes.has(call));
      if (write) {
        results.push({
          message: "Aggregate flow '" + name + "' declares semantics.safe: true but calls SQL write procedure '" + write + "'.",
          path: ["capability", "aggregates", index, "flows", name, "semantics", "safe"],
        });
      }
    }
  });
  return results;
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
  // Report the first gap only, as the engine does: one typo such as position
  // 100000 must not produce a diagnostic per missing position.
  for (let position = 1; position <= taken.size; position++) {
    if (!taken.has(position)) {
      results.push({
        message: where + " has no parameter at position " + position + "; positions must be contiguous from 1.",
        path: [...path, "storedProcedure"],
      });
      break;
    }
  }
}

function checkPlaceholders(namespace, name, procedure, path, dialect, results) {
  if (typeof procedure.statement !== "string") {
    return;
  }
  const scan = placeholders(procedure.statement, dialect);
  const inputs = isObject(procedure.inputParameters) ? procedure.inputParameters : {};
  const where = "SQL procedure '" + namespace + "." + name + "'";
  const statementPath = [...path, "statement"];
  // the first only: a section reports once, not again on its closing tag
  for (const tag of scan.malformed.slice(0, 1)) {
    results.push({
      message: where + " uses {{" + tag + "}}, which is not a placeholder: a statement only takes {{name}}, naming one input.",
      path: statementPath,
    });
  }
  for (const what of new Set(scan.jdbc)) {
    results.push({ message: where + " uses " + what + ".", path: statementPath });
  }
  for (const tag of new Set(scan.quoted)) {
    results.push({
      message: where + " has placeholder {{" + tag + "}} inside quotes, where it would not be bound; write it outside the quotes.",
      path: statementPath,
    });
  }
  for (const used of new Set(scan.names)) {
    if (!Object.prototype.hasOwnProperty.call(inputs, used)) {
      results.push({
        message: where + " uses placeholder {{" + used + "}}, which matches no declared input.",
        path: statementPath,
      });
    }
  }
  for (const key of Object.keys(inputs)) {
    if (!scan.names.includes(key)) {
      results.push({
        message: where + " declares input '" + key + "', which its statement never uses ({{" + key + "}}).",
        path: [...path, "inputParameters", key],
      });
    }
  }
}

/** A `{{name}}` tag inside quoted text, dotted names included; `'{{1,2},{3,4}}'` does not match. */
const QUOTED_PLACEHOLDER = /\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g;

/**
 * The `{{name}}` placeholders of a statement, in order, outside literals and comments (`names`),
 * the tags written inside literals or quoted identifiers (`quoted`), and the `{{…}}` tags that are
 * not a plain input name (`malformed`), and the JDBC syntax written outside literals (`jdbc`).
 */
function placeholders(sql, dialect = {}) {
  const scan = { names: [], quoted: [], malformed: [], jdbc: [] };
  const quotedFrom = (start, end) => {
    for (const match of sql.slice(start, end).matchAll(QUOTED_PLACEHOLDER)) {
      scan.quoted.push(match[1]);
    }
    return end;
  };
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
      i = quotedFrom(i, endOfQuoted(sql, i, "]", false));
    } else if (c === "'" || c === '"' || c === "`") {
      const backslash = dialect.mysql
        ? c !== "`"
        : c === "'" && i > 0 && /[Ee]/.test(sql[i - 1]) && !/\w/.test(sql[i - 2] || "");
      i = quotedFrom(i, endOfQuoted(sql, i, c, backslash));
    } else if (dialect.oracle && alternativeQuote(sql, i) > 0) {
      i = quotedFrom(i, endOfAlternativeQuote(sql, alternativeQuote(sql, i)));
    } else if (!dialect.mysql && c === "$" && !/[\w$]/.test(sql[i - 1] || "") && !/\d/.test(sql[i + 1] || "")) {
      const tag = /^\$\w*\$/.exec(sql.slice(i));
      if (tag) {
        const end = sql.indexOf(tag[0], i + tag[0].length);
        i = quotedFrom(i, end < 0 ? n : end + tag[0].length);
      } else {
        i++;
      }
    } else if (c === "{" && sql[i + 1] === "{") {
      const close = sql.indexOf("}}", i + 2);
      const tag = sql.slice(i + 2, close < 0 ? n : close).trim();
      if (close >= 0 && /^[A-Za-z0-9_-]+$/.test(tag)) {
        scan.names.push(tag);
      } else {
        scan.malformed.push(tag);
      }
      i = close < 0 ? n : close + 2;
    } else if (c === "{") {
      scan.jdbc.push("a JDBC escape ({fn ...}, {d '...'}, {call ...}); write the database's own SQL instead");
      i++;
    } else if (c === "?" && dialect.known && !dialect.postgresql) {
      // with a bound URL the database is unknown, and on PostgreSQL ? is a JSONB operator
      scan.jdbc.push("a JDBC ? marker; name the input with a {{name}} placeholder instead");
      i++;
    } else if (c === ":" && dialect.oracle && /[A-Za-z_]/.test(sql[i + 1] || "")) {
      scan.jdbc.push("an Oracle :name bind marker; name the input with a {{name}} placeholder instead");
      i++;
    } else {
      i++;
    }
  }
  return scan;
}

/** The index of the opening quote of an Oracle `q'…'` or `nq'…'` literal starting at `i`, or -1. */
function alternativeQuote(sql, i) {
  if (/[\w$]/.test(sql[i - 1] || "")) {
    return -1;
  }
  const q = /[Nn]/.test(sql[i]) ? i + 1 : i;
  return /[Qq]/.test(sql[q] || "") && sql[q + 1] === "'" && q + 2 < sql.length ? q + 1 : -1;
}

/** The index just past an Oracle alternative-quoted literal whose opening quote is at `quote`. */
function endOfAlternativeQuote(sql, quote) {
  const open = sql[quote + 1];
  const close = { "[": "]", "{": "}", "<": ">", "(": ")" }[open] || open;
  const end = sql.indexOf(close + "'", quote + 2);
  return end < 0 ? sql.length : end + 2;
}

/**
 * `--`, and on MySQL `#`. MySQL needs a space or a control character after `--`, as the engine
 * reads it: an ASCII character up to the space, or DEL. Any other character, a Unicode space
 * included, makes `--` two minus signs there.
 */
function startsLineComment(sql, i, mysql) {
  if (mysql && sql[i] === "#") {
    return true;
  }
  if (sql[i] !== "-" || sql[i + 1] !== "-") {
    return false;
  }
  if (!mysql || i + 2 >= sql.length) {
    return true;
  }
  const next = sql.charCodeAt(i + 2);
  return next <= 0x20 || next === 0x7f;
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
