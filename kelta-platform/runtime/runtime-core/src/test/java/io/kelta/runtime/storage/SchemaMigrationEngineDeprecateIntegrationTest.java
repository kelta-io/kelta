package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.storage.SchemaMigrationEngine.MigrationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One pod's destructive migration drops a column; the other pods then refresh over NATS and run
 * the non-destructive {@code migrateSchema}, which deprecates the removed field. On real Postgres a
 * {@code COMMENT ON COLUMN} for a missing column fails, which used to abort the rest of the call and
 * skip the history row.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("SchemaMigrationEngine — deprecate of an already-dropped column against Postgres")
class SchemaMigrationEngineDeprecateIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"));

    private JdbcTemplate jdbc;
    private SchemaMigrationEngine engine;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"acme-corp\"");
        engine = new SchemaMigrationEngine(jdbc);
    }

    private static CollectionDefinition collection(String name, String... fields) {
        CollectionDefinitionBuilder builder = new CollectionDefinitionBuilder().name(name);
        for (String field : fields) {
            builder.addField(new FieldDefinitionBuilder().name(field).type(FieldType.STRING).build());
        }
        return builder.build();
    }

    private void createTable(TableRef ref) {
        jdbc.execute("DROP TABLE IF EXISTS " + ref.toSql());
        jdbc.execute("CREATE TABLE " + ref.toSql()
                + " (id VARCHAR(36) PRIMARY KEY, name TEXT, old_field TEXT)");
    }

    private long historyCount(String collection, MigrationType type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM kelta_migrations WHERE collection_name = ? AND migration_type = ?",
                Long.class, collection, type.name());
    }

    private boolean columnExists(TableRef ref, String column) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                Integer.class, ref.schema(), ref.tableName(), column) > 0;
    }

    private void assertDeprecateToleratesDroppedColumn(String collectionName, TableRef ref) {
        createTable(ref);
        jdbc.execute("ALTER TABLE " + ref.toSql() + " DROP COLUMN old_field");

        assertThatCode(() -> engine.migrateSchema(
                collection(collectionName, "name", "old_field"),
                collection(collectionName, "name", "new_field"),
                ref)).doesNotThrowAnyException();

        assertThat(columnExists(ref, "new_field")).as("ADD_COLUMN in the same call still ran").isTrue();
        assertThat(historyCount(collectionName, MigrationType.ADD_COLUMN)).isEqualTo(1);
        assertThat(historyCount(collectionName, MigrationType.DEPRECATE_COLUMN)).isEqualTo(1);
    }

    @Test
    @DisplayName("public table: deprecate of a dropped column is skipped, recorded, and the ADD still runs")
    void publicSchemaDroppedColumn() {
        assertDeprecateToleratesDroppedColumn("dep_public", TableRef.publicSchema("dep_public"));
    }

    @Test
    @DisplayName("schema-qualified table: deprecate of a dropped column is skipped, recorded, and the ADD still runs")
    void tenantSchemaDroppedColumn() {
        assertDeprecateToleratesDroppedColumn("dep_tenant", TableRef.tenantSchema("acme-corp", "dep_tenant"));
    }

    @Test
    @DisplayName("schema-qualified table: an existing column is still commented as deprecated")
    void tenantSchemaExistingColumnIsCommented() {
        TableRef ref = TableRef.tenantSchema("acme-corp", "dep_existing");
        createTable(ref);
        // Same-named table in public without the column: the lookup must use the TableRef's schema.
        jdbc.execute("DROP TABLE IF EXISTS dep_existing");
        jdbc.execute("CREATE TABLE dep_existing (id VARCHAR(36) PRIMARY KEY)");

        engine.migrateSchema(
                collection("dep_existing", "name", "old_field"),
                collection("dep_existing", "name"),
                ref);

        String comment = jdbc.queryForObject(
                "SELECT col_description('\"acme-corp\".\"dep_existing\"'::regclass, a.attnum)"
                        + " FROM pg_attribute a"
                        + " WHERE a.attrelid = '\"acme-corp\".\"dep_existing\"'::regclass AND a.attname = 'old_field'",
                String.class);
        assertThat(comment).startsWith("DEPRECATED:");
        assertThat(historyCount("dep_existing", MigrationType.DEPRECATE_COLUMN)).isEqualTo(1);
    }
}
