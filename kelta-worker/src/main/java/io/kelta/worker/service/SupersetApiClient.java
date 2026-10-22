package io.kelta.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Apache Superset REST API.
 *
 * <p>Authenticates using username/password to obtain a JWT access token,
 * then uses that token for subsequent API calls. Tokens are cached and
 * refreshed on 401 responses.
 *
 * @since 1.0.0
 */
public class SupersetApiClient {

    private static final Logger log = LoggerFactory.getLogger(SupersetApiClient.class);

    private final String baseUrl;
    private final String adminUsername;
    private final String adminPassword;
    private final RestTemplate restTemplate;
    private final SupersetDatabaseTarget databaseTarget;

    private volatile String accessToken;

    public SupersetApiClient(String baseUrl, String adminUsername, String adminPassword,
                              RestTemplate restTemplate, SupersetDatabaseTarget databaseTarget) {
        this.baseUrl = baseUrl;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.restTemplate = restTemplate;
        this.databaseTarget = databaseTarget;
    }

    // ----------------------------------------------------------------
    // Authentication
    // ----------------------------------------------------------------

    /**
     * Authenticates with Superset and caches the access token.
     */
    @SuppressWarnings("unchecked")
    public synchronized void authenticate() {
        var body = Map.of(
                "username", adminUsername,
                "password", adminPassword,
                "provider", "db",
                "refresh", true
        );
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = restTemplate.exchange(
                baseUrl + "/api/v1/security/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        if (response.getBody() != null) {
            this.accessToken = (String) response.getBody().get("access_token");
            log.debug("Superset authentication successful");
        }
    }

    /**
     * Performs a health check against Superset.
     */
    public void healthCheck() {
        restTemplate.getForEntity(baseUrl + "/health", String.class);
    }

    // ----------------------------------------------------------------
    // Database connections
    // ----------------------------------------------------------------

    /**
     * Creates a database connection in Superset for a tenant using a
     * dedicated per-tenant PostgreSQL user.
     *
     * <p>The PostgreSQL user already has {@code search_path} and
     * {@code app.current_tenant_id} configured via {@code ALTER ROLE ... SET},
     * so no extra connection options are needed. The connection points at the configured
     * {@link SupersetDatabaseTarget}.
     *
     * @param tenantId   the tenant UUID
     * @param tenantSlug the tenant slug (used as schema name)
     * @param dbUsername the per-tenant PostgreSQL username
     * @param dbPassword the password for the per-tenant DB user
     * @return the database connection ID, or -1 on failure
     */
    public int createDatabaseConnection(String tenantId, String tenantSlug,
                                         String dbUsername, String dbPassword) {
        var body = Map.of(
                "database_name", "kelta-" + tenantSlug,
                "engine", "postgresql",
                "sqlalchemy_uri", databaseTarget.sqlalchemyUri(dbUsername, dbPassword),
                "expose_in_sqllab", true,
                "allow_ctas", false,
                "allow_cvas", false,
                "allow_dml", false,
                "allow_run_async", true
        );

        var response = executeWithAuth(HttpMethod.POST, "/api/v1/database/", body, Map.class);
        if (response != null && response.get("id") != null) {
            int dbId = ((Number) response.get("id")).intValue();
            log.info("Created Superset database connection for tenant '{}' (dbId={}, pgUser={})",
                    tenantSlug, dbId, dbUsername);
            return dbId;
        }
        return -1;
    }

    /**
     * Finds the database connection ID for a tenant by name.
     */
    public int findDatabaseId(String tenantSlug) {
        var response = executeWithAuth(HttpMethod.GET,
                "/api/v1/database/?q=(filters:!((col:database_name,opr:eq,value:'kelta-" + tenantSlug + "')))",
                null, Map.class);

        if (response != null) {
            var result = (List<?>) response.get("result");
            if (result != null && !result.isEmpty()) {
                var first = (Map<?, ?>) result.get(0);
                return ((Number) first.get("id")).intValue();
            }
        }
        return -1;
    }

    /**
     * Deletes a database connection by ID.
     */
    public void deleteDatabaseConnection(int databaseId) {
        executeWithAuth(HttpMethod.DELETE, "/api/v1/database/" + databaseId, null, Map.class);
        log.info("Deleted Superset database connection (id={})", databaseId);
    }

    // ----------------------------------------------------------------
    // Datasets
    // ----------------------------------------------------------------

    /**
     * Creates a virtual dataset in Superset.
     *
     * @param databaseId the database connection ID
     * @param tableName  the table name
     * @param sql        the virtual SQL query
     * @return the dataset ID, or -1 on failure
     */
    public int createDataset(int databaseId, String tableName, String sql) {
        var body = Map.of(
                "database", databaseId,
                "table_name", tableName,
                "sql", sql,
                "is_managed_externally", true
        );

        var response = executeWithAuth(HttpMethod.POST, "/api/v1/dataset/", body, Map.class);
        if (response != null && response.get("id") != null) {
            return ((Number) response.get("id")).intValue();
        }
        return -1;
    }

    /**
     * Lists datasets for a given database connection.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listDatasets(int databaseId) {
        var response = executeWithAuth(HttpMethod.GET,
                "/api/v1/dataset/?q=(filters:!((col:database,opr:rel_o_m,value:" + databaseId + ")),page_size:1000)",
                null, Map.class);

        if (response != null && response.get("result") != null) {
            return (List<Map<String, Object>>) response.get("result");
        }
        return List.of();
    }

    /**
     * Deletes a dataset by ID.
     */
    public void deleteDataset(int datasetId) {
        executeWithAuth(HttpMethod.DELETE, "/api/v1/dataset/" + datasetId, null, Map.class);
    }

    // ----------------------------------------------------------------
    // Dashboards
    // ----------------------------------------------------------------

    /**
     * Lists all dashboards in Superset, enriched with embedded UUIDs.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listDashboards() {
        var response = executeWithAuth(HttpMethod.GET,
                "/api/v1/dashboard/?q=(page_size:100)",
                null, Map.class);

        if (response != null && response.get("result") != null) {
            var dashboards = (List<Map<String, Object>>) response.get("result");
            // Enrich each dashboard with its embedded UUID
            for (var dashboard : dashboards) {
                Object idObj = dashboard.get("id");
                if (idObj != null) {
                    String embeddedUuid = getEmbeddedUuid(((Number) idObj).intValue());
                    if (embeddedUuid != null) {
                        dashboard.put("embedded_id", embeddedUuid);
                    }
                }
            }
            return dashboards;
        }
        return List.of();
    }

    /**
     * Gets the embedded UUID for a dashboard, or null if not embedded.
     */
    @SuppressWarnings("unchecked")
    public String getEmbeddedUuid(int dashboardId) {
        var response = executeWithAuth(HttpMethod.GET,
                "/api/v1/dashboard/" + dashboardId + "/embedded",
                null, Map.class);

        if (response != null && response.get("result") != null) {
            var result = (Map<String, Object>) response.get("result");
            return (String) result.get("uuid");
        }
        return null;
    }

    // ----------------------------------------------------------------
    // Guest tokens
    // ----------------------------------------------------------------

    /**
     * Generates a guest token for embedding a dashboard.
     *
     * @param dashboardId the Superset dashboard ID (UUID string)
     * @param user        map with "username" key
     * @param rls         list of RLS rule maps with "clause" and optional "dataset" keys
     * @return the guest token string, or null on failure
     */
    public String generateGuestToken(String dashboardId, Map<String, String> user,
                                      List<Map<String, Object>> rls) {
        var body = Map.of(
                "user", user,
                "resources", List.of(Map.of("type", "dashboard", "id", dashboardId)),
                "rls", rls
        );

        var response = executeWithAuth(HttpMethod.POST, "/api/v1/security/guest_token/", body, Map.class);
        if (response != null && response.get("token") != null) {
            return (String) response.get("token");
        }
        return null;
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> T executeWithAuth(HttpMethod method, String path, Object body, Class<T> responseType) {
        if (accessToken == null) {
            authenticate();
        }

        try {
            return doRequest(method, path, body, responseType);
        } catch (Exception e) {
            // Retry once after re-auth (token may have expired)
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                log.debug("Superset token expired — re-authenticating");
                authenticate();
                return doRequest(method, path, body, responseType);
            }
            log.error("Superset API call failed: {} {} — {}", method, path, e.getMessage());
            return null;
        }
    }

    private <T> T doRequest(HttpMethod method, String path, Object body, Class<T> responseType) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        var entity = new HttpEntity<>(body, headers);
        var response = restTemplate.exchange(baseUrl + path, method, entity, responseType);
        return response.getBody();
    }

}
