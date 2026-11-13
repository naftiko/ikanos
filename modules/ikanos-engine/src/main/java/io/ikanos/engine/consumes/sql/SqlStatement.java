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
import java.util.Set;

/**
 * An authored SQL statement, parsed once when the adapter is built.
 *
 * <p>The parser walks the text once. It rewrites each {@code :name} placeholder to a JDBC
 * {@code ?} and records the names in order, so a placeholder used twice is bound twice. It skips
 * string literals ({@code '…'}, {@code E'…'}), quoted identifiers ({@code "…"}, {@code `…`}),
 * PostgreSQL dollar-quoted strings ({@code $$…$$}, {@code $tag$…$tag$}), comments and
 * {@code ::} casts. The same walk collects the keywords outside literals and comments, which
 * classify the statement, and rejects a second statement after a {@code ;}.</p>
 *
 * <p>With {@code mysql} set (MySQL and MariaDB URLs), the walk follows that dialect instead:
 * backslash escapes in {@code '…'} and {@code "…"} literals, {@code #} line comments,
 * {@code --} as a comment only when whitespace follows, and no dollar quoting. Reading MySQL text
 * with the standard rules would let {@code 'a\''} or {@code # '} hide a second statement.</p>
 *
 * <p>On PostgreSQL and SQL Server, block comments nest. On SQL Server and SQLite, {@code […]} is
 * a quoted identifier. SQL Server also runs a batch whose statements are not separated by
 * {@code ;}, so there the statement keywords of T-SQL are refused anywhere in a read
 * ({@link #SQLSERVER_STATEMENT_KEYWORDS}); they are reserved words, so no unquoted column can
 * carry them.</p>
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
    static final Set<String> SQLSERVER_STATEMENT_KEYWORDS = Set.of("ALTER", "BACKUP", "BEGIN",
            "BREAK", "BULK", "CHECKPOINT", "CLOSE", "COMMIT", "CONTINUE", "CREATE", "DBCC",
            "DEALLOCATE", "DECLARE", "DENY", "DROP", "DUMP", "EXEC", "EXECUTE", "GOTO", "GRANT",
            "IF", "KILL", "LOAD", "OPEN", "PRINT", "RAISERROR", "READTEXT", "RECONFIGURE",
            "RESTORE", "RETURN", "REVERT", "REVOKE", "ROLLBACK", "SAVE", "SET", "SETUSER",
            "SHUTDOWN", "TRUNCATE", "UPDATETEXT", "USE", "WAITFOR", "WHILE", "WRITETEXT");

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
        SQLITE;

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

    private SqlStatement(String sql, Dialect dialect, String jdbcSql, List<String> placeholders,
            List<String> keywords) {
        this.sql = sql;
        this.dialect = dialect;
        this.jdbcSql = jdbcSql;
        this.placeholders = Collections.unmodifiableList(placeholders);
        this.keywords = Collections.unmodifiableList(keywords);
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
        for (String keyword : keywords) {
            if (NON_READ_KEYWORDS.contains(keyword) || (dialect == Dialect.SQLSERVER
                    && SQLSERVER_STATEMENT_KEYWORDS.contains(keyword))) {
                return keyword;
            }
        }
        return null;
    }

    /** Keywords that start a statement that writes rows. */
    static final Set<String> WRITE_STARTS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

    /**
     * Classify a statement declared {@code readOnly: false}.
     *
     * <p>It must start with {@code INSERT}, {@code UPDATE}, {@code DELETE} or {@code MERGE}, or be
     * a {@code WITH} that runs one of them. Anything else is refused, which keeps out DDL
     * ({@code CREATE}, {@code ALTER}, {@code DROP}), {@code TRUNCATE}, grants, maintenance
     * ({@code VACUUM}, {@code ANALYZE}, {@code REINDEX}) and procedure calls, which belong in
     * {@code storedProcedure}. An {@code UPDATE} or {@code DELETE} must be followed by a
     * {@code WHERE}: without one it changes every row. A {@code MERGE} branch
     * ({@code THEN UPDATE}), MySQL's {@code ON DUPLICATE KEY UPDATE} and a locking
     * {@code FOR UPDATE} are not statements of their own and need none.</p>
     *
     * @return the write operation, such as {@code UPDATE}, for the span
     * @throws IllegalArgumentException naming the keyword that is refused
     */
    String writeOperation() {
        String first = firstKeyword();
        String operation = WRITE_STARTS.contains(first) ? first : null;
        if (operation == null && "WITH".equals(first)) {
            for (int i = 1; i < keywords.size() && operation == null; i++) {
                if (WRITE_STARTS.contains(keywords.get(i)) && !isClause(i)) {
                    operation = keywords.get(i);
                }
            }
        }
        if (operation == null) {
            throw new IllegalArgumentException("a write statement must be INSERT, UPDATE, DELETE"
                    + " or MERGE (or a WITH that runs one); it starts with "
                    + (first.isEmpty() ? "nothing" : first));
        }
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            if (("UPDATE".equals(keyword) || "DELETE".equals(keyword)) && !isClause(i)
                    && !keywords.subList(i + 1, keywords.size()).contains("WHERE")) {
                throw new IllegalArgumentException(keyword
                        + " without WHERE would change every row; add a WHERE clause");
            }
        }
        return operation;
    }

    /** @return whether the keyword at {@code i} is a clause of another statement, not its own */
    private boolean isClause(int i) {
        String previous = i > 0 ? keywords.get(i - 1) : "";
        return "THEN".equals(previous) || "FOR".equals(previous) || "KEY".equals(previous);
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
                int end = endOfLineComment(sql, i, dialect != Dialect.SQLITE);
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
                int end = endOfQuoted(sql, i, '\'', backslashEscapes);
                out.append(sql, i, end);
                i = end;
            } else if (c == '[' && dialect.bracketIdentifiers()) {
                int end = endOfQuoted(sql, i, ']', false);
                out.append(sql, i, end);
                i = end;
            } else if (c == '"' || c == '`') {
                int end = endOfQuoted(sql, i, c, mysql && c == '"');
                out.append(sql, i, end);
                i = end;
            } else if (!mysql && c == '$' && dollarTag(sql, i) != null) {
                String tag = dollarTag(sql, i);
                int end = sql.indexOf(tag, i + tag.length());
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "statement has an unterminated dollar-quoted string");
                }
                out.append(sql, i, end + tag.length());
                i = end + tag.length();
            } else if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < n && isWordStart(sql.charAt(i + 1))) {
                int end = i + 1;
                while (end < n && isWordChar(sql.charAt(end))) {
                    end++;
                }
                placeholders.add(sql.substring(i + 1, end));
                out.append('?');
                i = end;
            } else if (c == ';') {
                ended = true;
                i++;
            } else if (isWordStart(c)) {
                // $ continues an unquoted identifier (audit$delete is one name), though not a
                // named placeholder
                int end = i + 1;
                while (end < n && (isWordChar(sql.charAt(end)) || sql.charAt(end) == '$')) {
                    end++;
                }
                keywords.add(sql.substring(i, end).toUpperCase(Locale.ROOT));
                out.append(sql, i, end);
                i = end;
            } else if (c == '?' && dialect == Dialect.POSTGRESQL) {
                // a JSONB operator (?, ?|, ?&): pgjdbc reads a lone ? as a bind marker
                out.append("??");
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return new SqlStatement(sql, dialect, out.toString().strip(), placeholders, keywords);
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
     * @return the index of the line end that closes the comment at {@code i}: a line feed, or
     *     also a carriage return where the database ends a comment there
     */
    private static int endOfLineComment(String sql, int i, boolean carriageReturn) {
        for (int j = i; j < sql.length(); j++) {
            char c = sql.charAt(j);
            if (c == '\n' || (carriageReturn && c == '\r')) {
                return j;
            }
        }
        return sql.length();
    }

    private static boolean startsComment(String sql, int i, boolean mysql) {
        return startsLineComment(sql, i, mysql) || (i + 1 < sql.length()
                && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*');
    }

    /** {@code --}, and in MySQL {@code #}; MySQL needs whitespace after {@code --}. */
    private static boolean startsLineComment(String sql, int i, boolean mysql) {
        if (mysql && sql.charAt(i) == '#') {
            return true;
        }
        if (sql.charAt(i) != '-' || i + 1 >= sql.length() || sql.charAt(i + 1) != '-') {
            return false;
        }
        return !mysql || i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2));
    }

    /** @return the index just past the closing quote */
    private static int endOfQuoted(String sql, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
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
