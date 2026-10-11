package io.kelta.worker.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Ownership must never downgrade. Uninstall keeps provenance rows, so after uninstall + reinstall
 * the module's own collection "already exists" and is re-recorded {@code ADOPTED}; overwriting the
 * row would make it unremovable forever.
 *
 * <p>A mocked {@link JdbcTemplate} cannot execute the {@code ON CONFLICT} clause, so this pins the
 * SQL text that does the work.
 */
@DisplayName("ModuleProvenanceStore")
class ModuleProvenanceStoreTest {

    private JdbcTemplate jdbcTemplate;
    private ModuleProvenanceStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new ModuleProvenanceStore(jdbcTemplate);
    }

    private String capturedSql() {
        store.record("t1", "kelta-billing", "1.0.0", "COLLECTION", "billing_plans", "col-1",
                ModuleProvenanceStore.OWNERSHIP_ADOPTED, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), anyString(), eq("t1"), eq("kelta-billing"),
                eq("1.0.0"), eq("COLLECTION"), eq("billing_plans"), eq("col-1"),
                eq(ModuleProvenanceStore.OWNERSHIP_ADOPTED), isNull());
        return sql.getValue().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("re-recording keeps an existing CREATED row CREATED")
    void keepsCreatedOwnership() {
        assertThat(capturedSql()).contains(
                "ON CONFLICT (tenant_id, module_id, resource_type, natural_key) DO UPDATE SET",
                "ownership = CASE WHEN module_provisioned_resource.ownership = 'CREATED' "
                        + "THEN 'CREATED' ELSE EXCLUDED.ownership END");
    }

    @Test
    @DisplayName("re-recording keeps an existing CREATED row's original content_hash")
    void keepsCreatedContentHash() {
        assertThat(capturedSql()).contains(
                "content_hash = CASE WHEN module_provisioned_resource.ownership = 'CREATED' "
                        + "THEN module_provisioned_resource.content_hash ELSE EXCLUDED.content_hash END");
    }

    @Test
    @DisplayName("an ADOPTED row takes the new ownership, so ADOPTED can upgrade to CREATED")
    void adoptedCanUpgrade() {
        String sql = capturedSql();
        assertThat(sql).contains("ELSE EXCLUDED.ownership END");
        // The bare overwrite this replaced must be gone.
        assertThat(sql).doesNotContain("ownership = EXCLUDED.ownership,")
                .doesNotContain("content_hash = EXCLUDED.content_hash");
    }

    @Test
    @DisplayName("a re-record without a resource id keeps the one already stored")
    void keepsResourceIdWhenAbsent() {
        assertThat(capturedSql()).contains(
                "resource_id = COALESCE(EXCLUDED.resource_id, module_provisioned_resource.resource_id)");
    }
}
