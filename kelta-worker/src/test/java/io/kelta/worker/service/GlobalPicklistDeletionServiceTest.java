package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.PicklistSystemCollections;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.PicklistValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting a global picklist removes its {@code picklist-values} rows in the same transaction.
 *
 * <p>Runs against a real (H2) database and a real {@link DataSourceTransactionManager}, so the
 * rollback assertions observe actual rows, not mock interactions. The query engine stands in for
 * storage by deleting the value row over the same data source, as the physical storage adapter
 * does.
 */
@DisplayName("GlobalPicklistDeletionService")
class GlobalPicklistDeletionServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String OTHER_TENANT = "tenant-2";
    private static final String PICKLIST = "gp-stage";
    private static final String OTHER_PICKLIST = "gp-priority";
    private static final String FIELD = "field-status";

    private JdbcTemplate jdbc;
    private QueryEngine queryEngine;
    private GlobalPicklistDeletionService service;
    private final CollectionDefinition valuesDefinition = PicklistSystemCollections.picklistValues();

    @BeforeEach
    void setUp() {
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("""
                CREATE TABLE global_picklist (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    name VARCHAR(100) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE picklist_value (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    picklist_source_type VARCHAR(20) NOT NULL,
                    picklist_source_id VARCHAR(36) NOT NULL,
                    label VARCHAR(255) NOT NULL
                )""");

        insertPicklist(PICKLIST, TENANT, "Stage");
        insertValue("v-new", TENANT, "GLOBAL", PICKLIST, "new");
        insertValue("v-won", TENANT, "GLOBAL", PICKLIST, "won");
        insertValue("v-lost", TENANT, "GLOBAL", PICKLIST, "lost");
        insertPicklist(OTHER_PICKLIST, TENANT, "Priority");
        insertValue("v-high", TENANT, "GLOBAL", OTHER_PICKLIST, "high");
        insertValue("v-low", TENANT, "GLOBAL", OTHER_PICKLIST, "low");
        insertValue("v-open", TENANT, "FIELD", FIELD, "open");
        insertValue("v-closed", TENANT, "FIELD", FIELD, "closed");
        // A field-local value whose source id collides with the picklist id: the source type
        // still keeps it out of the cleanup.
        insertValue("v-field-collide", TENANT, "FIELD", PICKLIST, "collide");
        // Same picklist id in another tenant.
        insertValue("v-other-tenant", OTHER_TENANT, "GLOBAL", PICKLIST, "new");

        queryEngine = mock(QueryEngine.class);
        when(queryEngine.delete(eq(valuesDefinition), anyString()))
                .thenAnswer(inv -> jdbc.update("DELETE FROM picklist_value WHERE id = ?",
                        (String) inv.getArgument(1)) == 1);
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get("picklist-values")).thenReturn(valuesDefinition);

        service = new GlobalPicklistDeletionService(new PicklistValueRepository(jdbc), queryEngine,
                registry, new DataSourceTransactionManager(db));
    }

    @Test
    @DisplayName("Deleting a picklist removes every value whose source is that picklist")
    void deletesValuesOfThePicklist() {
        ResponseEntity<Void> response = deleteAsTenant(PICKLIST, () -> deletePicklistRow(PICKLIST));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(picklistExists(PICKLIST)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM picklist_value WHERE tenant_id = ? AND picklist_source_type = 'GLOBAL' "
                        + "AND picklist_source_id = ?", Integer.class, TENANT, PICKLIST)).isZero();
    }

    @Test
    @DisplayName("Values of other picklists, field-local values and other tenants are untouched")
    void leavesOtherValuesAlone() {
        deleteAsTenant(PICKLIST, () -> deletePicklistRow(PICKLIST));

        assertThat(remainingValueIds()).containsExactlyInAnyOrder(
                "v-high", "v-low", "v-open", "v-closed", "v-field-collide", "v-other-tenant");
        assertThat(picklistExists(OTHER_PICKLIST)).isTrue();
    }

    @Test
    @DisplayName("Values are deleted through the query engine so their record events fire")
    void deletesThroughQueryEngine() {
        deleteAsTenant(PICKLIST, () -> deletePicklistRow(PICKLIST));

        verify(queryEngine).delete(valuesDefinition, "v-new");
        verify(queryEngine).delete(valuesDefinition, "v-won");
        verify(queryEngine).delete(valuesDefinition, "v-lost");
    }

    @Test
    @DisplayName("A failure during the values cleanup rolls back the picklist delete and earlier value deletes")
    void cleanupFailureRollsBackPicklistDelete() {
        AtomicInteger calls = new AtomicInteger();
        when(queryEngine.delete(eq(valuesDefinition), anyString())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("storage unavailable");
            }
            return jdbc.update("DELETE FROM picklist_value WHERE id = ?", (String) inv.getArgument(1)) == 1;
        });

        assertThatThrownBy(() -> deleteAsTenant(PICKLIST, () -> deletePicklistRow(PICKLIST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage unavailable");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(picklistExists(PICKLIST)).isTrue();
        assertThat(remainingValueIds()).contains("v-new", "v-won", "v-lost").hasSize(9);
    }

    @Test
    @DisplayName("When the picklist delete does not succeed, its values are kept")
    void notFoundKeepsValues() {
        ResponseEntity<Void> response = deleteAsTenant("gp-missing", () -> ResponseEntity.notFound().build());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(queryEngine, never()).delete(any(), anyString());
        assertThat(remainingValueIds()).hasSize(9);
    }

    @Test
    @DisplayName("A picklist with no values deletes cleanly")
    void picklistWithoutValues() {
        insertPicklist("gp-empty", TENANT, "Empty");

        ResponseEntity<Void> response = deleteAsTenant("gp-empty", () -> deletePicklistRow("gp-empty"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(picklistExists("gp-empty")).isFalse();
        assertThat(remainingValueIds()).hasSize(9);
    }

    private ResponseEntity<Void> deleteAsTenant(String picklistId,
                                                Supplier<ResponseEntity<Void>> deletePicklist) {
        return TenantContext.callWithTenant(TENANT, () -> service.delete(picklistId, deletePicklist));
    }

    /** Stands in for the generic collection delete the controller delegates to. */
    private ResponseEntity<Void> deletePicklistRow(String id) {
        return jdbc.update("DELETE FROM global_picklist WHERE id = ?", id) == 1
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private void insertPicklist(String id, String tenantId, String name) {
        jdbc.update("INSERT INTO global_picklist (id, tenant_id, name) VALUES (?, ?, ?)", id, tenantId, name);
    }

    // H2 reserves "value", so the test table carries only the label; the cleanup never reads either.
    private void insertValue(String id, String tenantId, String sourceType, String sourceId, String label) {
        jdbc.update("INSERT INTO picklist_value (id, tenant_id, picklist_source_type, picklist_source_id, label) "
                + "VALUES (?, ?, ?, ?, ?)", id, tenantId, sourceType, sourceId, label);
    }

    private boolean picklistExists(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM global_picklist WHERE id = ?", Integer.class, id) == 1;
    }

    private List<String> remainingValueIds() {
        return jdbc.queryForList("SELECT id FROM picklist_value", String.class);
    }
}
