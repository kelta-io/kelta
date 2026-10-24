package io.kelta.testharness.scenarios;

import com.github.dockerjava.api.model.ContainerNetwork;
import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the premise every tenant-isolation scenario rests on: kelta-worker and kelta-auth reach
 * Postgres as a role that row-level security applies to.
 *
 * <p>A superuser — or any role with {@code BYPASSRLS} — never evaluates a policy. A harness whose
 * services connect as one passes every isolation scenario whatever the policies say, which is how
 * {@code SandboxAdminLoginScenarioTest} stayed green while the code it covers was broken in
 * production. These tests fail the run if the application role gains either attribute, if a
 * service is configured with another user, or if a session a service actually holds is anyone
 * else's.
 */
@DisplayName("Service Database Role Scenario")
class ServiceDatabaseRoleScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("the application role is neither a superuser nor BYPASSRLS, and owns the database")
    void applicationRoleIsSubjectToRowLevelSecurity() throws SQLException {
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement("""
                     SELECT r.rolsuper, r.rolbypassrls,
                            (SELECT pg_get_userbyid(datdba) FROM pg_database
                              WHERE datname = current_database()) AS db_owner
                       FROM pg_roles r
                      WHERE r.rolname = ?""")) {
            ps.setString(1, KeltaStack.appDbUsername());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("role %s exists", KeltaStack.appDbUsername()).isTrue();
                assertThat(rs.getBoolean("rolsuper"))
                        .as("%s must not be a superuser: a superuser never evaluates a policy",
                                KeltaStack.appDbUsername())
                        .isFalse();
                assertThat(rs.getBoolean("rolbypassrls"))
                        .as("%s must be NOBYPASSRLS, as the production application role is",
                                KeltaStack.appDbUsername())
                        .isFalse();
                assertThat(rs.getString("db_owner"))
                        .as("the application role owns its database, as production's does")
                        .isEqualTo(KeltaStack.appDbUsername());
            }
        }
    }

    @Test
    @DisplayName("kelta-worker and kelta-auth are configured to connect as the application role")
    void servicesAreConfiguredWithTheApplicationRole() {
        assertThat(KeltaStack.WORKER.getEnvMap())
                .containsEntry("SPRING_DATASOURCE_USERNAME", KeltaStack.appDbUsername());
        assertThat(KeltaStack.AUTH.getEnvMap())
                .containsEntry("SPRING_DATASOURCE_USERNAME", KeltaStack.appDbUsername());
    }

    /**
     * The configuration above could be overridden by a second datasource or a property the
     * service reads instead, so this checks what Postgres sees: every session opened from a
     * service container's address belongs to the application role.
     */
    @Test
    @DisplayName("every database session kelta-worker and kelta-auth hold is the application role's")
    void everyServiceSessionIsTheApplicationRole() throws SQLException {
        assertThat(sessionUsersOf(KeltaStack.WORKER))
                .as("kelta-worker's database sessions")
                .isNotEmpty()
                .containsOnly(KeltaStack.appDbUsername());
        assertThat(sessionUsersOf(KeltaStack.AUTH))
                .as("kelta-auth's database sessions")
                .isNotEmpty()
                .containsOnly(KeltaStack.appDbUsername());
    }

    /** The user of every authenticated session connected from {@code container}'s address. */
    private List<String> sessionUsersOf(GenericContainer<?> container) throws SQLException {
        String address = harnessNetworkAddress(container);
        try (Connection db = openDbConnection();
             PreparedStatement ps = db.prepareStatement("""
                     SELECT usename FROM pg_stat_activity
                      WHERE backend_type = 'client backend'
                        AND usename IS NOT NULL
                        AND host(client_addr) = ?""")) {
            ps.setString(1, address);
            List<String> users = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(rs.getString(1));
                }
            }
            return users;
        }
    }

    private static String harnessNetworkAddress(GenericContainer<?> container) {
        String networkId = KeltaStack.NETWORK.getId();
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
                .filter(n -> networkId.equals(n.getNetworkID()))
                .map(ContainerNetwork::getIpAddress)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        container.getDockerImageName() + " is not on the harness network"));
    }
}
