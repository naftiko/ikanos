/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.engine.consumes.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An authored SQL statement, parsed once when the adapter is built.
 *
 * <p>The parser walks the text once. It rewrites each {@code {{name}}} placeholder to a JDBC
 * {@code ?} and records the names in order, so a placeholder used twice is bound twice. The
 * placeholder has the Mustache form used elsewhere in a capability, but the statement is never
 * rendered as a template: the value is always bound, never written into the SQL. It skips
 * string literals ({@code '…'}, {@code E'…'}), quoted identifiers ({@code "…"}, {@code `…`}),
 * PostgreSQL dollar-quoted strings ({@code $$…$$}, {@code $tag$…$tag$}) and comments. A
 * placeholder inside a literal or a quoted identifier is refused, since it would reach the
 * database as text. JDBC syntax is refused, so a statement holds only the database's own SQL
 * and {@code {{name}}}: an escape ({@code {fn …}}, {@code {d '…'}}, {@code {call …}}), a
 * {@code ?} marker (except on PostgreSQL, where {@code ?} is a JSONB operator and is doubled
 * for the driver), and on Oracle a {@code :name} marker. The same walk collects the keywords outside literals and comments, which
 * classify the statement, and rejects a second statement after a {@code ;}.</p>
 *
 * <p>With {@code mysql} set (MySQL and MariaDB URLs), the walk follows that dialect instead:
 * backslash escapes in {@code '…'} and {@code "…"} literals, {@code #} line comments,
 * {@code --} as a comment only when a space or control character follows, and no dollar
 * quoting. Reading MySQL text with the standard rules would let {@code 'a\''} or {@code # '}
 * hide a second statement. A backslash right before a quote is refused, since MySQL reads it as
 * text under {@code NO_BACKSLASH_ESCAPES}, and a line comment ended by a carriage return alone is
 * refused outside PostgreSQL, since MySQL and SQLite end it at the next line feed only.</p>
 *
 * <p>On PostgreSQL and SQL Server, block comments nest. On SQL Server and SQLite, {@code […]} is
 * a quoted identifier. SQL Server also runs a batch whose statements are not separated by
 * {@code ;}, so there the statement keywords of T-SQL are refused anywhere in a read or a
 * write, but for the {@code SET} of an {@code UPDATE} ({@link #SQLSERVER_STATEMENT_KEYWORDS});
 * they are reserved words, so no unquoted column can carry them.</p>
 *
 * <p>The SQL itself is sent to the driver verbatim otherwise: Ikanos never rewrites the dialect.</p>
 */
final class SqlStatement {

    /** Keywords that start a statement that only reads. */
    static final Set<String> READ_STARTS = Set.of("SELECT", "WITH", "VALUES");

    /**
     * Keywords that make a statement starting with a read keyword write anyway, wherever they
     * appear outside literals and comments: a data-modifying CTE ({@code WITH d AS (DELETE …)}),
     * or {@code SELECT … INTO}, which creates a table on PostgreSQL and SQL Server and writes a
     * file on MySQL. {@code UPDATE} also catches {@code SELECT … FOR UPDATE}, a locking read that
     * a read-only connection refuses anyway. The list stays short on purpose: a broader one would
     * refuse ordinary columns such as {@code comment} or functions such as {@code replace()}.
     */
    static final Set<String> NON_READ_KEYWORDS =
            Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "INTO");

    /**
     * T-SQL keywords that start a statement other than a read. SQL Server accepts
     * {@code SELECT 1 DROP TABLE t} as two statements, so a read that holds one of them is
     * refused. All are reserved words in T-SQL. {@code FETCH} is left out: a read uses it in
     * {@code OFFSET … FETCH NEXT}.
     */
    static final Set<String> SQLSERVER_STATEMENT_KEYWORDS = Set.of("ADD", "ALTER", "BACKUP",
            "BEGIN", "BREAK", "BULK", "CHECKPOINT", "CLOSE", "COMMIT", "CONTINUE", "CREATE", "DBCC",
            "DEALLOCATE", "DECLARE", "DENY", "DROP", "DUMP", "EXEC", "EXECUTE", "GOTO", "GRANT",
            "IF", "KILL", "LOAD", "OPEN", "PRINT", "RAISERROR", "READTEXT", "RECONFIGURE",
            "RESTORE", "RETURN", "REVERT", "REVOKE", "ROLLBACK", "SAVE", "SET", "SETUSER",
            "SHUTDOWN", "TRUNCATE", "UPDATETEXT", "USE", "WAITFOR", "WHILE", "WRITETEXT");

    /**
     * T-SQL statements that start with a word that is not reserved, or that a read also uses
     * ({@code END} closes a {@code CASE}), keyed by that word, with the word that follows it:
     * {@code ENABLE TRIGGER}, {@code DISABLE TRIGGER}, and the Service Broker statements
     * {@code SEND ON CONVERSATION}, {@code END CONVERSATION} and {@code MOVE CONVERSATION}.
     * SQL Server runs {@code SELECT 1 DISABLE TRIGGER ALL ON t} as two statements, so a read that
     * holds such a pair is refused. Alone, the first words stay usable as column names.
     */
    static final Map<String, String> SQLSERVER_TWO_WORD_STATEMENTS = Map.of("ENABLE", "TRIGGER",
            "DISABLE", "TRIGGER", "SEND", "ON", "END", "CONVERSATION", "MOVE", "CONVERSATION");

    /**
     * T-SQL statement keywords that are not reserved and take no fixed next word, refused
     * anywhere in a SQL Server read: {@code RECEIVE}, which takes messages off a Service Broker
     * queue ({@code SELECT 1 AS a RECEIVE * FROM q} runs as two statements). A column of that
     * name is quoted, as in {@code [receive]}.
     */
    static final Set<String> SQLSERVER_UNRESERVED_STATEMENT_KEYWORDS = Set.of("RECEIVE");

    /** The name in a {@code {{name}}} placeholder: an input key, as the schema allows it. */
    static final java.util.regex.Pattern PLACEHOLDER_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * A placeholder-shaped tag inside quoted text, dotted names included. A PostgreSQL array
     * literal such as {@code '{{1,2},{3,4}}'} does not match.
     */
    private static final java.util.regex.Pattern QUOTED_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    /** How a statement is read: the lexical rules that differ between databases. */
    enum Dialect {
        /** Standard SQL lexing, for databases not listed below. */
        STANDARD,
        /** MySQL and MariaDB: backslash escapes, {@code #} comments, no dollar quoting. */
        MYSQL,
        /** PostgreSQL: nested block comments. */
        POSTGRESQL,
        /** SQL Server: nested block comments, {@code […]} identifiers, unseparated batches. */
        SQLSERVER,
        /** SQLite: {@code […]} identifiers. */
        SQLITE,
        /** Oracle: alternative-quoted literals ({@code q'[…]'}, {@code nq'{…}'}). */
        ORACLE;

        boolean nestedComments() {
            return this == POSTGRESQL || this == SQLSERVER;
        }

        boolean bracketIdentifiers() {
            return this == SQLSERVER || this == SQLITE;
        }
    }

    private final String sql;
    private final Dialect dialect;
    private final String jdbcSql;
    private final List<String> placeholders;
    private final List<String> keywords;
    private final List<Integer> depths;
    /** Where each keyword starts in {@link #sql}. */
    private final List<Integer> offsets;
    private final boolean terminated;

    private SqlStatement(String sql, Dialect dialect, String jdbcSql, List<String> placeholders,
            List<String> keywords, List<Integer> depths, List<Integer> offsets,
            boolean terminated) {
        this.sql = sql;
        this.dialect = dialect;
        this.jdbcSql = jdbcSql;
        this.placeholders = Collections.unmodifiableList(placeholders);
        this.keywords = Collections.unmodifiableList(keywords);
        this.depths = Collections.unmodifiableList(depths);
        this.offsets = Collections.unmodifiableList(offsets);
        this.terminated = terminated;
    }

    /** @return whether the author ended the statement with {@code ;}, which jdbcSql drops */
    boolean terminated() {
        return terminated;
    }

    /** @return the statement as authored */
    String sql() {
        return sql;
    }

    /** @return the statement sent to the driver: placeholders replaced by {@code ?} */
    String jdbcSql() {
        return jdbcSql;
    }

    /** @return the placeholder names in order of appearance, repeats included */
    List<String> placeholders() {
        return placeholders;
    }

    /** @return the upper-case words outside literals, identifiers and comments, in order */
    List<String> keywords() {
        return keywords;
    }

    /** @return the first keyword, upper case, or an empty string */
    String firstKeyword() {
        return keywords.isEmpty() ? "" : keywords.get(0);
    }

    /**
     * @return the first keyword, outside literals and comments, that keeps this statement from
     *     being a read, or {@code null} when it only reads
     */
    String nonReadKeyword() {
        if (!READ_STARTS.contains(firstKeyword())) {
            return firstKeyword().isEmpty() ? "(empty)" : firstKeyword();
        }
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            if (NON_READ_KEYWORDS.contains(keyword) || (dialect == Dialect.SQLSERVER
                    && (SQLSERVER_STATEMENT_KEYWORDS.contains(keyword)
                            || SQLSERVER_UNRESERVED_STATEMENT_KEYWORDS.contains(keyword)
                            || (SQLSERVER_TWO_WORD_STATEMENTS.containsKey(keyword)
                                    && i + 1 < keywords.size()
                                    && SQLSERVER_TWO_WORD_STATEMENTS.get(keyword)
                                            .equals(keywords.get(i + 1)))))) {
                return keyword;
            }
        }
        return null;
    }

    /** Keywords that start a statement that writes rows. */
    static final Set<String> WRITE_STARTS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

    /**
     * Keywords that start a statement of their own, never a part of one INSERT, UPDATE, DELETE or
     * MERGE. SQL Server runs {@code UPDATE t SET a = 1 WHERE id = 1 COMMIT} as two statements, so
     * the COMMIT would land before {@code maxAffectedRows} could roll back; a write that holds one
     * of them is refused, on every database. A column with such a name is written quoted.
     */
    static final Set<String> OTHER_STATEMENT_KEYWORDS = Set.of("COMMIT", "ROLLBACK", "SAVEPOINT",
            "DROP", "CREATE", "ALTER", "TRUNCATE", "GRANT", "REVOKE", "EXEC", "EXECUTE",
            "SHUTDOWN", "WAITFOR", "KILL");

    /**
     * T-SQL statements that are not reserved words, so not in
     * {@link #SQLSERVER_STATEMENT_KEYWORDS}, yet change the database: {@code ENABLE TRIGGER} and
     * {@code DISABLE TRIGGER}. A write that holds them is refused on SQL Server, where
     * {@code UPDATE t SET a = 1 WHERE id = 1 DISABLE TRIGGER audit ON t} is two statements. A
     * column with such a name is written quoted.
     */
    static final Set<String> SQLSERVER_WRITE_REFUSED_KEYWORDS = Set.of("ENABLE", "DISABLE");

    /**
     * On SQL Server, which runs a batch whose statements are not separated by {@code ;}, find a
     * T-SQL statement keyword ({@link #SQLSERVER_STATEMENT_KEYWORDS},
     * {@link #SQLSERVER_WRITE_REFUSED_KEYWORDS}) in a write, as a read is checked. The {@code SET} of an {@code UPDATE} (one per {@code UPDATE}, a {@code MERGE}
     * branch included) is part of it; any other {@code SET} starts a statement of its own.
     *
     * @return the first such keyword, or {@code null} when there is none or the database is not
     *     SQL Server
     */
    private String sqlServerStatementKeyword() {
        if (dialect != Dialect.SQLSERVER) {
            return null;
        }
        int pendingSets = 0;
        for (String keyword : keywords) {
            if ("UPDATE".equals(keyword)) {
                pendingSets++;
            } else if ("SET".equals(keyword) && pendingSets > 0) {
                pendingSets--;
            } else if (SQLSERVER_STATEMENT_KEYWORDS.contains(keyword)
                    || SQLSERVER_WRITE_REFUSED_KEYWORDS.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    /**
     * Classify a statement declared {@code readOnly: false}.
     *
     * <p>It must run exactly one {@code INSERT}, {@code UPDATE}, {@code DELETE} or {@code MERGE},
     * as its main statement: first, or after a {@code WITH} whose parts only read. Anything else
     * is refused. That keeps out DDL ({@code CREATE}, {@code ALTER}, {@code DROP}),
     * {@code TRUNCATE}, grants, maintenance ({@code VACUUM}, {@code ANALYZE}, {@code REINDEX}) and
     * procedure calls, which belong in {@code storedProcedure}. It also keeps out a write inside a
     * {@code WITH} or a subquery: the driver reports only the main statement's row count, so such a
     * write would escape {@code maxAffectedRows}.</p>
     *
     * <p>An {@code UPDATE} or {@code DELETE} needs a {@code WHERE} of its own, at its own level of
     * parentheses: a {@code WHERE} in a subquery does not limit the rows it changes. A
     * {@code MERGE} branch ({@code THEN UPDATE}), an upsert ({@code ON CONFLICT DO UPDATE},
     * {@code ON DUPLICATE KEY UPDATE}) and a locking {@code FOR UPDATE} are clauses, not
     * statements, and need none.</p>
     *
     * @return the write operation, such as {@code UPDATE}, for the span
     * @throws IllegalArgumentException naming the keyword that is refused
     */
    String writeOperation() {
        String first = firstKeyword();
        if (!WRITE_STARTS.contains(first) && !"WITH".equals(first)) {
            throw new IllegalArgumentException("a write statement must be INSERT, UPDATE, DELETE"
                    + " or MERGE (or a WITH that runs one); it starts with "
                    + (first.isEmpty() ? "nothing" : first));
        }
        for (String keyword : keywords) {
            if (OTHER_STATEMENT_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException("a write statement runs one INSERT, UPDATE,"
                        + " DELETE or MERGE; it also runs " + keyword
                        + " (quote it if it names a column)");
            }
        }
        String other = sqlServerStatementKeyword();
        if (other != null) {
            throw new IllegalArgumentException("a write statement runs one INSERT, UPDATE,"
                    + " DELETE or MERGE; on SQL Server it also runs " + other
                    + " (quote it if it names a column)");
        }
        String operation = null;
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            if (!WRITE_STARTS.contains(keyword) || isClause(i)
                    || isMergeDeleteClause(operation, i)) {
                continue;
            }
            if (depths.get(i) != 0) {
                throw new IllegalArgumentException(keyword + " inside a WITH or a subquery is"
                        + " refused: its rows would not count against maxAffectedRows");
            }
            if (operation != null) {
                throw new IllegalArgumentException("a write statement runs one INSERT, UPDATE,"
                        + " DELETE or MERGE; it also runs " + keyword);
            }
            operation = keyword;
            if (("UPDATE".equals(keyword) || "DELETE".equals(keyword)) && !hasOwnWhere(i)) {
                throw new IllegalArgumentException(keyword
                        + " without WHERE would change every row; add a WHERE clause");
            }
        }
        if (operation == null) {
            throw new IllegalArgumentException("a write statement must be INSERT, UPDATE, DELETE"
                    + " or MERGE (or a WITH that runs one); this WITH only reads");
        }
        refuseSqlServerSelect(operation);
        return operation;
    }

    /** Set operators that join one SELECT to the next. */
    private static final Set<String> SET_OPERATORS = Set.of("UNION", "ALL", "EXCEPT", "INTERSECT");

    /**
     * On SQL Server, refuse a {@code SELECT} outside parentheses that is not the source of an
     * {@code INSERT}: SQL Server runs {@code UPDATE t SET a = 1 SELECT … INTO copy FROM t} as two
     * statements. The source of an {@code INSERT} is its first {@code SELECT}, with no
     * {@code VALUES} before it, and the ones a set operator joins to it.
     */
    private void refuseSqlServerSelect(String operation) {
        if (dialect != Dialect.SQLSERVER) {
            return;
        }
        boolean values = false;
        boolean selected = false;
        for (int i = 0; i < keywords.size(); i++) {
            if (depths.get(i) != 0) {
                continue;
            }
            String keyword = keywords.get(i);
            if ("VALUES".equals(keyword)) {
                values = true;
            } else if ("SELECT".equals(keyword)) {
                boolean insertSource = "INSERT".equals(operation) && !values && !selected;
                boolean joined = selected && SET_OPERATORS.contains(keywords.get(i - 1));
                if (!insertSource && !joined) {
                    throw new IllegalArgumentException("a write statement runs one INSERT,"
                            + " UPDATE, DELETE or MERGE; on SQL Server it also runs a SELECT");
                }
                selected = true;
            }
        }
    }

    /**
     * @return whether a {@code WHERE} follows the keyword at {@code i} at the same level of
     *     parentheses, before that level closes
     */
    boolean hasOwnWhere(int i) {
        int depth = depths.get(i);
        for (int j = i + 1; j < keywords.size(); j++) {
            if (depths.get(j) < depth) {
                return false;
            }
            if (depths.get(j) == depth && "WHERE".equals(keywords.get(j))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the keyword at {@code i} is Oracle's {@code DELETE WHERE} clause of a
     *     {@code MERGE} ({@code WHEN MATCHED THEN UPDATE SET … DELETE WHERE …}), not a statement
     */
    private boolean isMergeDeleteClause(String operation, int i) {
        return "MERGE".equals(operation) && "DELETE".equals(keywords.get(i))
                && depths.get(i) == 0 && i + 1 < keywords.size()
                && "WHERE".equals(keywords.get(i + 1)) && keywords.subList(0, i).contains("UPDATE");
    }

    /**
     * @return whether the statement has a {@code RETURNING} clause, or SQL Server's
     *     {@code OUTPUT} clause: {@code OUTPUT} outside parentheses, followed directly by
     *     {@code inserted.}, {@code deleted.} or {@code $action}. A column named {@code output}
     *     is not one, nor are columns {@code output, inserted} listed together.
     */
    boolean returnsRows() {
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            if ("RETURNING".equals(keyword)) {
                return true;
            }
            if ("OUTPUT".equals(keyword) && dialect == Dialect.SQLSERVER && depths.get(i) == 0
                    && i + 1 < keywords.size() && outputColumn(i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the keyword after the {@code OUTPUT} at {@code i} starts an output column:
     *     {@code inserted.} or {@code deleted.}, or {@code $action}, with only spaces between
     */
    private boolean outputColumn(int i) {
        String next = keywords.get(i + 1);
        int start = offsets.get(i + 1);
        String between = sql.substring(offsets.get(i) + keywords.get(i).length(), start).strip();
        if ("ACTION".equals(next)) {
            return "$".equals(between);
        }
        if (!between.isEmpty() || !("INSERTED".equals(next) || "DELETED".equals(next))) {
            return false;
        }
        String after = sql.substring(start + next.length()).stripLeading();
        return after.startsWith(".");
    }

    /** @return whether the keyword at {@code i} is a clause of another statement, not its own */
    boolean isClause(int i) {
        String previous = i > 0 ? keywords.get(i - 1) : "";
        return "THEN".equals(previous) || "FOR".equals(previous) || "KEY".equals(previous)
                || "DO".equals(previous);
    }

    /**
     * Parse an authored statement.
     *
     * @param sql the statement as written in the capability
     * @return the parsed statement
     * @throws IllegalArgumentException when the text holds more than one statement, or an
     *     unterminated literal, identifier or comment. The message never quotes the SQL.
     */
    static SqlStatement parse(String sql) {
        return parse(sql, Dialect.STANDARD);
    }

    /**
     * Parse an authored statement in a dialect.
     *
     * @param sql the statement as written in the capability
     * @param mysql whether to read it as MySQL / MariaDB text
     * @return the parsed statement
     * @throws IllegalArgumentException as {@link #parse(String)}
     */
    static SqlStatement parse(String sql, boolean mysql) {
        return parse(sql, mysql ? Dialect.MYSQL : Dialect.STANDARD);
    }

    /**
     * Parse an authored statement in a dialect.
     *
     * @param sql the statement as written in the capability
     * @param dialect the database's lexical rules
     * @return the parsed statement
     * @throws IllegalArgumentException as {@link #parse(String)}
     */
    static SqlStatement parse(String sql, Dialect dialect) {
        boolean mysql = dialect == Dialect.MYSQL;
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("statement is empty");
        }
        StringBuilder out = new StringBuilder(sql.length());
        List<String> placeholders = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        int depth = 0;
        int n = sql.length();
        int i = 0;
        boolean ended = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (ended && !Character.isWhitespace(c) && !startsComment(sql, i, mysql)) {
                throw new IllegalArgumentException(
                        "statement holds more than one SQL statement; declare one per procedure");
            }
            if (startsLineComment(sql, i, mysql)) {
                int end = endOfLineComment(sql, i, dialect);
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                if (mysql && executableComment(sql, i)) {
                    throw new IllegalArgumentException("statement holds a MySQL executable"
                            + " comment (/*! ... */ or /*M! ... */), which runs its content;"
                            + " write that SQL outside a comment");
                }
                int end = endOfBlockComment(sql, i, dialect.nestedComments());
                out.append(sql, i, end);
                i = end;
            } else if (c == '\'') {
                boolean backslashEscapes = mysql || (i > 0 && (sql.charAt(i - 1) == 'E'
                        || sql.charAt(i - 1) == 'e') && (i < 2 || !isWordChar(sql.charAt(i - 2))));
                int end = endOfQuoted(sql, i, '\'', backslashEscapes, mysql);
                refusePlaceholderIn(sql, i, end, "a string literal");
                out.append(sql, i, end);
                i = end;
            } else if (c == '[' && dialect.bracketIdentifiers()) {
                int end = endOfQuoted(sql, i, ']', false, false);
                refusePlaceholderIn(sql, i, end, "a quoted identifier");
                out.append(sql, i, end);
                i = end;
            } else if (c == '"' || c == '`') {
                int end = endOfQuoted(sql, i, c, mysql && c == '"', mysql && c == '"');
                refusePlaceholderIn(sql, i, end,
                        mysql && c == '"' ? "a string literal" : "a quoted identifier");
                out.append(sql, i, end);
                i = end;
            } else if (!mysql && c == '$' && dollarTag(sql, i) != null) {
                String tag = dollarTag(sql, i);
                int end = sql.indexOf(tag, i + tag.length());
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "statement has an unterminated dollar-quoted string");
                }
                refusePlaceholderIn(sql, i, end + tag.length(), "a dollar-quoted string");
                out.append(sql, i, end + tag.length());
                i = end + tag.length();
            } else if (c == '{' && i + 1 < n && sql.charAt(i + 1) == '{') {
                int close = sql.indexOf("}}", i + 2);
                if (close < 0) {
                    throw new IllegalArgumentException(
                            "statement has an unterminated {{ placeholder");
                }
                placeholders.add(placeholderName(sql.substring(i + 2, close)));
                out.append('?');
                i = close + 2;
            } else if (c == '{') {
                throw new IllegalArgumentException("statement uses a JDBC escape ({fn ...},"
                        + " {d '...'}, {ts '...'}, {oj ...}, {call ...}); write the database's"
                        + " own SQL instead");
            } else if (c == '?' && dialect != Dialect.POSTGRESQL) {
                throw new IllegalArgumentException("statement uses a JDBC ? marker; name the"
                        + " input with a {{name}} placeholder instead");
            } else if (c == ':' && dialect == Dialect.ORACLE && i + 1 < n
                    && isWordStart(sql.charAt(i + 1))) {
                throw new IllegalArgumentException("statement uses an Oracle :name bind"
                        + " marker, which the driver would expect a value for; name the input"
                        + " with a {{name}} placeholder instead");
            } else if (c == ';') {
                ended = true;
                i++;
            } else if (dialect == Dialect.ORACLE && alternativeQuote(sql, i) > 0) {
                int end = endOfAlternativeQuote(sql, alternativeQuote(sql, i));
                refusePlaceholderIn(sql, i, end, "a string literal");
                out.append(sql, i, end);
                i = end;
            } else if (isWordStart(c)) {
                // $ continues an unquoted identifier (audit$delete is one name)
                int end = i + 1;
                while (end < n && (isWordChar(sql.charAt(end)) || sql.charAt(end) == '$')) {
                    end++;
                }
                keywords.add(sql.substring(i, end).toUpperCase(Locale.ROOT));
                depths.add(depth);
                offsets.add(i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '?') {
                // PostgreSQL only (others refuse it above): a JSONB operator (?, ?|, ?&), which
                // pgjdbc would read as a bind marker unless doubled
                out.append("??");
                i++;
            } else {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                }
                out.append(c);
                i++;
            }
        }
        return new SqlStatement(sql, dialect, out.toString().strip(), placeholders, keywords,
                depths, offsets, ended);
    }

    /**
     * @param tag the text between {@code {{} and {@code }}}
     * @return the input name the placeholder binds
     * @throws IllegalArgumentException when the tag is not a plain input name: a Mustache section,
     *     partial, comment or unescaped form, or a dotted name such as {@code {{step.field}}}
     */
    static String placeholderName(String tag) {
        String name = tag.strip();
        if (PLACEHOLDER_NAME.matcher(name).matches()) {
            return name;
        }
        if (name.startsWith("{")) {
            throw new IllegalArgumentException("statement uses a {{{ placeholder; write"
                    + " {{name}}: every value is bound, none is written into the SQL");
        }
        if (!name.isEmpty() && "#^/>!&=".indexOf(name.charAt(0)) >= 0) {
            throw new IllegalArgumentException("statement uses a Mustache '" + name.charAt(0)
                    + "' tag; a statement only takes {{name}} placeholders, each bound to"
                    + " one input");
        }
        throw new IllegalArgumentException("statement placeholder {{" + name + "}} must name"
                + " one input of the procedure (letters, digits, '-' and '_'); pass other"
                + " values through the input from the calling step's 'with'");
    }

    /**
     * Refuse a {@code {{name}}} placeholder between {@code start} and {@code end}: inside quotes
     * it would be sent as text, not bound.
     */
    private static void refusePlaceholderIn(String sql, int start, int end, String what) {
        java.util.regex.Matcher tag = QUOTED_PLACEHOLDER.matcher(sql).region(start, end);
        if (tag.find()) {
            throw new IllegalArgumentException("statement has placeholder {{" + tag.group(1)
                    + "}} inside " + what + ", where it would not be bound; write it outside"
                    + " the quotes, and concatenate in SQL when the value is part of a text");
        }
    }

    /**
     * @return the index of the opening quote of an Oracle alternative-quoted literal
     *     ({@code q'…'} or {@code nq'…'}) that starts at word start {@code i}, or {@code -1}
     */
    private static int alternativeQuote(String sql, int i) {
        int q = i;
        if ((sql.charAt(q) == 'n' || sql.charAt(q) == 'N') && q + 1 < sql.length()) {
            q++;
        }
        boolean quoted = (sql.charAt(q) == 'q' || sql.charAt(q) == 'Q')
                && q + 2 < sql.length() && sql.charAt(q + 1) == '\'';
        return quoted ? q + 1 : -1;
    }

    /**
     * @return the index just past an Oracle alternative-quoted literal whose opening quote is at
     *     {@code quote}: it closes with its delimiter, or the matching bracket, then a quote
     */
    private static int endOfAlternativeQuote(String sql, int quote) {
        char open = sql.charAt(quote + 1);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            case '(' -> ')';
            default -> open;
        };
        int end = sql.indexOf(String.valueOf(close) + '\'', quote + 2);
        if (Character.isWhitespace(open) || end < 0) {
            throw new IllegalArgumentException("statement has an unterminated quoted string");
        }
        return end + 2;
    }

    /**
     * @return the index just past the {@code *}{@code /} that closes the comment at {@code start},
     *     counting nested comments when the dialect nests them
     */
    private static int endOfBlockComment(String sql, int start, boolean nested) {
        int depth = 0;
        int i = start;
        while (i + 1 < sql.length()) {
            if (sql.charAt(i) == '/' && sql.charAt(i + 1) == '*' && (nested || depth == 0)) {
                depth++;
                i += 2;
            } else if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        throw new IllegalArgumentException("statement has an unterminated comment");
    }

    /** @return whether the comment at {@code i} is MySQL's {@code /*!} or MariaDB's {@code /*M!} */
    private static boolean executableComment(String sql, int i) {
        return sql.startsWith("/*!", i) || sql.regionMatches(true, i, "/*M!", 0, 4);
    }

    /**
     * @return the index of the line end that closes the comment at {@code i}: a line feed, or on
     *     PostgreSQL also a carriage return
     * @throws IllegalArgumentException on a carriage return not followed by a line feed in other
     *     dialects: MySQL, MariaDB and SQLite read the comment on to the next line feed, so the
     *     text after it would be code for one reading and comment for the other
     */
    private static int endOfLineComment(String sql, int i, Dialect dialect) {
        for (int j = i; j < sql.length(); j++) {
            char c = sql.charAt(j);
            if (c == '\n') {
                return j;
            }
            if (c == '\r') {
                if (dialect == Dialect.POSTGRESQL
                        || (j + 1 < sql.length() && sql.charAt(j + 1) == '\n')) {
                    return j;
                }
                throw new IllegalArgumentException("statement has a line comment ended by a"
                        + " carriage return alone, which databases read differently; end the"
                        + " line with a line feed");
            }
        }
        return sql.length();
    }

    private static boolean startsComment(String sql, int i, boolean mysql) {
        return startsLineComment(sql, i, mysql) || (i + 1 < sql.length()
                && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*');
    }

    /**
     * {@code --}, and in MySQL {@code #}. MySQL needs a space or a control character after
     * {@code --}, as its lexer reads it: an ASCII character up to the space, or DEL. Any other
     * character, a Unicode space included, makes {@code --} two minus signs there.
     */
    private static boolean startsLineComment(String sql, int i, boolean mysql) {
        if (mysql && sql.charAt(i) == '#') {
            return true;
        }
        if (sql.charAt(i) != '-' || i + 1 >= sql.length() || sql.charAt(i + 1) != '-') {
            return false;
        }
        if (!mysql || i + 2 >= sql.length()) {
            return true;
        }
        char next = sql.charAt(i + 2);
        return next <= ' ' || next == 0x7F;
    }

    /**
     * @param refuseEscapedQuote whether a backslash before the quote is refused: MySQL reads it as
     *     an escape, but as text followed by the closing quote when {@code sql_mode} holds
     *     {@code NO_BACKSLASH_ESCAPES}, so the two readings would split the statement differently
     * @return the index just past the closing quote
     */
    private static int endOfQuoted(String sql, int start, char quote, boolean backslashEscapes,
            boolean refuseEscapedQuote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (refuseEscapedQuote && c == '\\' && i + 1 < sql.length()
                    && sql.charAt(i + 1) == quote) {
                throw new IllegalArgumentException("statement escapes a quote with a backslash,"
                        + " which MySQL reads as the end of the text under NO_BACKSLASH_ESCAPES;"
                        + " double the quote instead");
            }
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("statement has an unterminated "
                + (quote == '\'' ? "string literal" : "quoted identifier"));
    }

    /** @return the opening {@code $tag$} at {@code i}, or {@code null} */
    private static String dollarTag(String sql, int i) {
        // $1 is a positional parameter and a$b an identifier, not a dollar quote
        if ((i > 0 && isWordChar(sql.charAt(i - 1)))
                || (i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1)))) {
            return null;
        }
        int j = i + 1;
        while (j < sql.length() && isWordChar(sql.charAt(j))) {
            j++;
        }
        if (j < sql.length() && sql.charAt(j) == '$') {
            return sql.substring(i, j + 1);
        }
        return null;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
