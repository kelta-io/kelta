package io.kelta.worker.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SupersetDatabaseTarget")
class SupersetDatabaseTargetTest {

    private static final String CLUSTER_URL =
            "jdbc:postgresql://postgres-rw.postgres.svc.cluster.local:5432/emf_control_plane";

    @Test
    @DisplayName("defaults to the host, port and database of the worker's own datasource")
    void derivesFromDatasourceUrl() {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(CLUSTER_URL, "", "", "");

        assertThat(target).isEqualTo(new SupersetDatabaseTarget(
                "postgres-rw.postgres.svc.cluster.local", 5432, "emf_control_plane"));
    }

    @Test
    @DisplayName("assumes 5432 and drops query parameters")
    void defaultPortAndQueryParameters() {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(
                "jdbc:postgresql://db.internal/kelta?sslmode=require&ApplicationName=worker", null, null, null);

        assertThat(target).isEqualTo(new SupersetDatabaseTarget("db.internal", 5432, "kelta"));
    }

    @Test
    @DisplayName("takes the first host of a multi-host URL")
    void firstOfSeveralHosts() {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(
                "jdbc:postgresql://primary:5433,replica:5434/kelta?targetServerType=primary", null, null, null);

        assertThat(target).isEqualTo(new SupersetDatabaseTarget("primary", 5433, "kelta"));
    }

    @Test
    @DisplayName("keeps an IPv6 host bracketed in the connection URI")
    void ipv6Host() {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(
                "jdbc:postgresql://[::1]:5433/kelta", null, null, null);

        assertThat(target.host()).isEqualTo("::1");
        assertThat(target.sqlalchemyUri("u", "p")).isEqualTo("postgresql://u:p@[::1]:5433/kelta");
    }

    @Test
    @DisplayName("explicit settings win over the datasource URL")
    void overridesWin() {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(
                CLUSTER_URL, "pg.example.internal", "6432", "analytics");

        assertThat(target).isEqualTo(new SupersetDatabaseTarget("pg.example.internal", 6432, "analytics"));
    }

    @Test
    @DisplayName("an unparseable datasource URL without a host setting fails with the setting to use")
    void unparseableWithoutOverride() {
        assertThatThrownBy(() -> SupersetDatabaseTarget.resolve("jdbc:h2:mem:test", "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERSET_DATABASE_HOST");
    }

    @Test
    @DisplayName("a full set of overrides needs no datasource URL")
    void overridesAlone() {
        SupersetDatabaseTarget target = SupersetDatabaseTarget.resolve(
                "jdbc:h2:mem:test", "pg", "5432", "kelta");

        assertThat(target).isEqualTo(new SupersetDatabaseTarget("pg", 5432, "kelta"));
    }

    @Test
    @DisplayName("rejects a port that is not a number")
    void badPort() {
        assertThatThrownBy(() -> SupersetDatabaseTarget.resolve(CLUSTER_URL, "", "postgres", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERSET_DATABASE_PORT");
    }

    @Test
    @DisplayName("percent-encodes the user and password in the connection URI")
    void encodesCredentials() {
        SupersetDatabaseTarget target = new SupersetDatabaseTarget("db", 5432, "kelta");

        assertThat(target.sqlalchemyUri("superset_acme", "p@ss:w/rd%+ x"))
                .isEqualTo("postgresql://superset_acme:p%40ss%3Aw%2Frd%25%2B%20x@db:5432/kelta");
    }
}
