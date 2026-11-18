/**
 * Spectral custom function: sql-procedures
 *
 * Enforces `ikanos-sql-placeholders-bound` on every `consumes: type: sql`
 * procedure (root `consumes` and `capability.consumes`):
 *
 * - every `{{name}}` placeholder in `statement` names a declared input key;
 * - every declared input is used by the statement;
 * - no placeholder sits inside a string literal or quoted identifier, where it
 *   would be sent as text instead of bound, and no `{{…}}` tag is anything but
 *   a plain input name (no sections, no dotted names).
 *
 * Placeholders are found the way the engine finds them: string literals,
 * quoted identifiers, dollar-quoted strings and comments are skipped. As in
 * the engine, the `connection.url` sets the dialect when it is
 * a literal JDBC URL: block comments nest on PostgreSQL and SQL Server, and
 * `[...]` is a quoted identifier on SQL Server and SQLite. On MySQL and
 * MariaDB, `#` starts a comment, `--` does only before whitespace, a backslash
 * escapes in quoted text, and there is no dollar quoting.
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
      checkPlaceholders(adapter.namespace, name, procedure, path, dialectOf(adapter), results);
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

function checkPlaceholders(namespace, name, procedure, path, dialect, results) {
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
