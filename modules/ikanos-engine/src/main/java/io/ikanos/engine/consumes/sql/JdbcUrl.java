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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A resolved JDBC URL: the hardening checks, and the parts that name the database in telemetry.
 *
 * <p>JDBC URLs have no common grammar beyond {@code jdbc:<subprotocol>:}. The parts are read on a
 * best-effort basis for the common shapes ({@code jdbc:postgresql://host:5432/db?…},
 * {@code jdbc:sqlserver://host:1433;databaseName=db}, {@code jdbc:oracle:thin:@//host:1521/svc},
 * {@code jdbc:sqlite:/path/file.db}). Credentials and driver properties are never part of
 * them.</p>
 */
final class JdbcUrl {

    /**
     * Driver properties that load classes, read local files, deserialize objects or run scripts
     * (PostgreSQL, MySQL Connector/J, MariaDB, SQL Server, H2). The URL comes from the operator, so
     * refusing them is defense in depth, in the URL and in {@code connection.properties} alike.
     * Compared case-insensitively.
     */
    static final Set<String> DENIED_PROPERTIES = Set.of(
            // PostgreSQL
            "socketfactory", "socketfactoryarg", "sslfactory", "sslfactoryarg",
            "sslhostnameverifier", "sslpasswordcallback", "authenticationpluginclassname",
            "loggerfile",
            // MySQL Connector/J and MariaDB
            "allowloadlocalinfile", "allowurlinlocalinfile", "allowloadlocalinfileinpath",
            "autodeserialize", "queryinterceptors", "statementinterceptors",
            "exceptioninterceptors", "connectionlifecycleinterceptors", "authenticationplugins",
            "defaultauthenticationplugin", "clientinfoprovider", "propertiestransform",
            "ha.loadbalancestrategy", "loadbalanceexceptionchecker", "serverconfigcachefactory",
            "parseinfocachefactory",
            // SQL Server
            "socketfactoryclass", "socketfactoryconstructorarg", "trustmanagerclass",
            "trustmanagerconstructorarg", "accesstokencallbackclass",
            // H2
            "init");

    /**
     * A property name: after {@code ?&;:} (query string and {@code ;key=value} forms) or after
     * {@code (,} (MySQL's {@code (host=…,key=value)} and {@code address=(key=value)} forms).
     */
    private static final Pattern PROPERTY_NAME =
            Pattern.compile("[?&;:(,]\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*=");
    private static final Pattern HOST_FORM =
            Pattern.compile("^(?:[^/]*@)?//(?:[^@/?;]*@)?([^/:?;]+)(?::\\d+)?(?:/([^?;/]*))?");

    private final String system;
    private final String host;
    private final String database;

    private JdbcUrl(String system, String host, String database) {
        this.system = system;
        this.host = host;
        this.database = database;
    }

    /**
     * Check a resolved URL and its driver properties.
     *
     * @param namespace the consumes namespace, for error messages
     * @param url the resolved JDBC URL
     * @param properties the driver properties, may be null
     * @return the parsed URL
     * @throws IllegalArgumentException when the URL is not a JDBC URL or names a denied driver
     *     property. The URL itself is never echoed, as it may come from a secret store.
     */
    static JdbcUrl check(String namespace, String url, Map<String, String> properties) {
        if (url == null || !url.regionMatches(true, 0, "jdbc:", 0, 5) || url.length() < 7) {
            throw new IllegalArgumentException("consumes '" + namespace
                    + "' connection.url must resolve to a JDBC URL (jdbc:<driver>:...)");
        }
        Matcher name = PROPERTY_NAME.matcher(percentDecoded(url));
        while (name.find()) {
            refuseIfDenied(namespace, name.group(1), "connection.url");
        }
        if (properties != null) {
            properties.keySet().forEach(key -> refuseIfDenied(namespace, key,
                    "connection.properties"));
        }
        return parse(url);
    }

    /** Drivers decode {@code %xx} in property names, so the check reads the decoded URL. */
    static String percentDecoded(String url) {
        if (url.indexOf('%') < 0) {
            return url;
        }
        try {
            return URLDecoder.decode(url.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return url;
        }
    }

    /** @return whether the database reads backslash escapes in literals and {@code #} comments */
    boolean mysqlDialect() {
        return dialect() == SqlStatement.Dialect.MYSQL;
    }

    /** @return the dialect statements are read in, from the driver subprotocol */
    SqlStatement.Dialect dialect() {
        return switch (system) {
            case "mysql", "mariadb" -> SqlStatement.Dialect.MYSQL;
            case "postgresql" -> SqlStatement.Dialect.POSTGRESQL;
            case "sqlserver", "jtds" -> SqlStatement.Dialect.SQLSERVER;
            case "sqlite" -> SqlStatement.Dialect.SQLITE;
            default -> SqlStatement.Dialect.STANDARD;
        };
    }

    private static void refuseIfDenied(String namespace, String property, String where) {
        if (property != null
                && DENIED_PROPERTIES.contains(property.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("consumes '" + namespace + "' " + where
                    + " sets driver property '" + property.trim()
                    + "', which can load classes, read local files or run scripts; it is refused");
        }
    }

    /** Parse the telemetry parts of a URL already known to start with {@code jdbc:}. */
    static JdbcUrl parse(String url) {
        String rest = url.substring(5);
        int colon = rest.indexOf(':');
        String system = (colon < 0 ? rest : rest.substring(0, colon)).toLowerCase(Locale.ROOT);
        String afterSystem = colon < 0 ? "" : rest.substring(colon + 1);
        // Oracle: jdbc:oracle:thin:@//host:1521/service or jdbc:oracle:thin:@host:1521:sid
        if (afterSystem.startsWith("thin:")) {
            afterSystem = afterSystem.substring(5);
            // jdbc:oracle:thin:user/password@host:1521:sid: drop the credentials
            int at = afterSystem.lastIndexOf('@');
            if (at > 0) {
                afterSystem = afterSystem.substring(at);
            }
        }
        String host = null;
        String database = null;
        Matcher hostForm = HOST_FORM.matcher(afterSystem);
        if (hostForm.find()) {
            host = hostOf(hostForm.group(1));
            database = emptyToNull(hostForm.group(2));
        } else if (afterSystem.startsWith("@")) {
            String[] parts = afterSystem.substring(1).split("[:/]");
            host = parts.length > 0 ? emptyToNull(parts[0]) : null;
            database = parts.length > 2 ? emptyToNull(parts[2]) : null;
        } else if (!afterSystem.isEmpty()) {
            // A file or in-memory database: the last path segment, without its properties.
            // A shape with an @ is not one of those and may carry credentials: it is not read.
            String path = afterSystem.split("[?;]", 2)[0];
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            database = path.indexOf('@') >= 0 ? null
                    : emptyToNull(slash < 0 ? path : path.substring(slash + 1));
        }
        if (database == null) {
            Matcher named = Pattern.compile("(?i)[;?&](?:databaseName|database)=([^;&]+)")
                    .matcher(afterSystem);
            if (named.find()) {
                database = named.group(1);
            }
        }
        return new JdbcUrl(system, host, database);
    }

    /**
     * @return the host name of an authority: MySQL's {@code (host=db,user=…,password=…)} and
     *     {@code address=(host=db)(password=…)} forms give only their {@code host} value, and an
     *     authority of another shape with {@code =} or {@code (} gives none, as it may carry
     *     credentials
     */
    private static String hostOf(String authority) {
        if (authority.indexOf('=') < 0 && authority.indexOf('(') < 0) {
            return authority;
        }
        Matcher host = Pattern.compile("(?i)(?:^|[(,])\\s*host\\s*=\\s*([^,()\\s]+)")
                .matcher(authority);
        return host.find() ? host.group(1) : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** @return the driver subprotocol, such as {@code postgresql}, {@code sqlserver} */
    String system() {
        return system;
    }

    /** @return the database host, or {@code null} for a file or in-memory database */
    String host() {
        return host;
    }

    /** @return the database name, or {@code null} when the URL does not say */
    String database() {
        return database;
    }

    /** @return the client span target: {@code <system>://<host>/<database>}, no credentials */
    String spanTarget() {
        StringBuilder target = new StringBuilder(system).append("://");
        target.append(host != null ? host : "local");
        if (database != null) {
            target.append('/').append(database);
        }
        return target.toString();
    }
}
