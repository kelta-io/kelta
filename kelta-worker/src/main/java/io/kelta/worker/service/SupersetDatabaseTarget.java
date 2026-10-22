package io.kelta.worker.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where Superset connects to Postgres for a tenant's database connection.
 *
 * <p>Superset reads the tenant schema as the per-tenant user {@link SupersetDatabaseUserService}
 * creates, so it needs the same database the worker writes. By default that is the host, port and
 * database of the worker's own {@code spring.datasource.url}; {@code SUPERSET_DATABASE_HOST},
 * {@code SUPERSET_DATABASE_PORT} and {@code SUPERSET_DATABASE_NAME} override each part when
 * Superset must reach Postgres by another address (for example around a connection pooler the
 * per-tenant users cannot authenticate through).
 *
 * @param host     host name or address (an IPv6 literal without brackets)
 * @param port     TCP port
 * @param database database name
 */
public record SupersetDatabaseTarget(String host, int port, String database) {

    private static final int DEFAULT_PORT = 5432;

    /** {@code jdbc:postgresql://host[:port][,host2[:port2]...]/database[?params]}. */
    private static final Pattern JDBC_URL =
            Pattern.compile("^jdbc:postgresql://([^/?]+)/([^?]*)(\\?.*)?$");

    /** One host entry: {@code [ipv6]:port}, {@code [ipv6]}, {@code host:port} or {@code host}. */
    private static final Pattern HOST_PORT =
            Pattern.compile("^(?:\\[([^]]+)]|([^:]+))(?::(\\d+))?$");

    /**
     * Resolves the target from the worker's datasource URL, with any non-blank override winning.
     *
     * @throws IllegalStateException when the URL cannot be read and no override replaces the part
     *                               it would have supplied, or the port override is not a number
     */
    public static SupersetDatabaseTarget resolve(String datasourceUrl, String hostOverride,
                                                 String portOverride, String databaseOverride) {
        String host = null;
        Integer port = null;
        String database = null;

        Matcher url = datasourceUrl == null ? null : JDBC_URL.matcher(datasourceUrl.trim());
        if (url != null && url.matches()) {
            Matcher first = HOST_PORT.matcher(url.group(1).split(",")[0]);
            if (first.matches()) {
                host = first.group(1) != null ? first.group(1) : first.group(2);
                port = first.group(3) != null ? Integer.valueOf(first.group(3)) : DEFAULT_PORT;
            }
            database = url.group(2).isBlank() ? null : url.group(2);
        }

        if (!isBlank(hostOverride)) {
            host = hostOverride.trim();
        }
        if (!isBlank(portOverride)) {
            try {
                port = Integer.valueOf(portOverride.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "SUPERSET_DATABASE_PORT (kelta.worker.superset.database-port) is not a port: " + portOverride);
            }
        }
        if (!isBlank(databaseOverride)) {
            database = databaseOverride.trim();
        }

        if (host == null || database == null) {
            throw new IllegalStateException("Cannot tell where Superset should reach Postgres: "
                    + "spring.datasource.url is not a jdbc:postgresql:// URL with a database. Set "
                    + "SUPERSET_DATABASE_HOST and SUPERSET_DATABASE_NAME "
                    + "(kelta.worker.superset.database-host / -name).");
        }
        return new SupersetDatabaseTarget(host, port == null ? DEFAULT_PORT : port, database);
    }

    /**
     * The SQLAlchemy URI for a per-tenant user. The user and password are percent-encoded, which
     * SQLAlchemy decodes, so a credential containing {@code @}, {@code :} or {@code /} cannot
     * change the host the connection goes to.
     */
    public String sqlalchemyUri(String username, String password) {
        String hostPart = host.contains(":") ? "[" + host + "]" : host;
        return "postgresql://" + encode(username) + ":" + encode(password)
                + "@" + hostPart + ":" + port + "/" + database;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
