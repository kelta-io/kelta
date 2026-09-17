package io.kelta.testharness;

import io.kelta.testharness.fixtures.AuthFixture;
import io.kelta.testharness.fixtures.TenantFixture;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Base class for cross-service scenario tests.
 *
 * <p>Extends with {@link KeltaStackExtension} so the full stack starts before
 * any test in this hierarchy runs. Provides convenience accessors for fixtures
 * and REST clients pointed at each service.
 */
@ExtendWith(KeltaStackExtension.class)
public abstract class ScenarioBase {

    protected final AuthFixture   auth   = new AuthFixture();
    protected final TenantFixture tenants = new TenantFixture();

    protected RestClient workerClient() {
        return RestClient.builder().baseUrl(KeltaStack.workerBaseUrl()).build();
    }

    protected RestClient authClient() {
        return RestClient.builder().baseUrl(KeltaStack.authBaseUrl()).build();
    }

    protected RestClient gatewayClient() {
        return RestClient.builder().baseUrl(KeltaStack.gatewayBaseUrl()).build();
    }

    protected RestClient gatewayClientWithToken(String token) {
        return RestClient.builder()
                .baseUrl(KeltaStack.gatewayBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    /**
     * Opens a direct JDBC connection to the harness database as the service DB
     * user, for asserting DB state the API doesn't expose. Caller closes it.
     *
     * <p>Note: this user is the Postgres bootstrap superuser (both the local
     * Testcontainers PG and the CI pool use the image's), so it <em>bypasses RLS</em>
     * even on FORCE'd tables — useful for planting and inspecting fixtures. Assertions
     * about RLS must connect as {@link #openAppDbConnection()} instead.
     */
    protected Connection openDbConnection() throws SQLException {
        return DriverManager.getConnection(
                KeltaStack.dbJdbcUrl(), KeltaStack.dbUsername(), KeltaStack.dbPassword());
    }

    /**
     * Opens a direct JDBC connection as the role the services themselves run as —
     * NOBYPASSRLS, so the policies are evaluated exactly as they are for kelta-worker.
     * Bind a tenant with {@code SET LOCAL app.current_tenant_id} to see what that tenant
     * sees. Caller closes it.
     */
    protected Connection openAppDbConnection() throws SQLException {
        return DriverManager.getConnection(
                KeltaStack.dbJdbcUrl(), KeltaStack.appDbUsername(), KeltaStack.appDbPassword());
    }

    /**
     * Opens a direct JDBC connection as an arbitrary role (e.g. a non-superuser
     * probe role created by the test to observe RLS). Caller closes it.
     */
    protected Connection openDbConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(KeltaStack.dbJdbcUrl(), username, password);
    }

    /**
     * Polls a URL until the response matches the expected status, up to {@code maxAttempts}.
     * Useful for waiting for NATS-propagated changes (e.g. new routes in the gateway).
     */
    protected void waitForStatus(RestClient client, String uri, HttpStatusCode expected, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                var status = client.get().uri(uri).retrieve()
                        .onStatus(s -> !s.equals(expected), (req, resp) -> {})
                        .toBodilessEntity()
                        .getStatusCode();
                if (status.equals(expected)) return;
            } catch (Exception ignored) {
                // not ready yet
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        throw new AssertionError("URL " + uri + " did not return " + expected + " after " + maxAttempts + " attempts");
    }
}
