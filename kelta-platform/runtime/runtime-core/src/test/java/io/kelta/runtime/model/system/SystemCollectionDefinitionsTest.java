package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SystemCollectionDefinitions")
class SystemCollectionDefinitionsTest {

    @Test
    @DisplayName("Should return all system collection definitions")
    void shouldReturnAllDefinitions() {
        List<CollectionDefinition> all = SystemCollectionDefinitions.all();
        assertNotNull(all);
        // Should have a large number of system collections (56+)
        assertTrue(all.size() >= 50, "Expected at least 50 system collections but got " + all.size());
    }

    @Test
    @DisplayName("Should return unmodifiable list from all()")
    void shouldReturnUnmodifiableList() {
        List<CollectionDefinition> all = SystemCollectionDefinitions.all();
        assertThrows(UnsupportedOperationException.class, () -> all.add(null));
    }

    @Test
    @DisplayName("Should have unique collection names")
    void shouldHaveUniqueNames() {
        List<CollectionDefinition> all = SystemCollectionDefinitions.all();
        long distinctNames = all.stream().map(CollectionDefinition::name).distinct().count();
        assertEquals(all.size(), distinctNames, "All collection names should be unique");
    }

    @Test
    @DisplayName("Should look up collections by name")
    void shouldLookUpByName() {
        Map<String, CollectionDefinition> byName = SystemCollectionDefinitions.byName();
        assertNotNull(byName);
        assertTrue(byName.containsKey("users"));
        assertTrue(byName.containsKey("tenants"));
        assertTrue(byName.containsKey("collections"));
        assertTrue(byName.containsKey("fields"));
        assertTrue(byName.containsKey("profiles"));
    }

    @Test
    @DisplayName("Should return unmodifiable map from byName()")
    void shouldReturnUnmodifiableMap() {
        Map<String, CollectionDefinition> byName = SystemCollectionDefinitions.byName();
        assertThrows(UnsupportedOperationException.class, () -> byName.put("test", null));
    }

    @Test
    @DisplayName("Should have correct SYSTEM_TENANT_ID constant")
    void shouldHaveSystemTenantId() {
        assertEquals("00000000-0000-0000-0000-000000000001", SystemCollectionDefinitions.SYSTEM_TENANT_ID);
    }

    @Test
    @DisplayName("Should define tenants collection correctly")
    void shouldDefineTenants() {
        CollectionDefinition tenants = SystemCollectionDefinitions.tenants();
        assertNotNull(tenants);
        assertEquals("tenants", tenants.name());
        assertEquals("Tenants", tenants.displayName());
        assertTrue(tenants.systemCollection());
    }

    @Test
    @DisplayName("Should define users collection correctly")
    void shouldDefineUsers() {
        CollectionDefinition users = SystemCollectionDefinitions.users();
        assertNotNull(users);
        assertEquals("users", users.name());
        assertEquals("Users", users.displayName());
        assertTrue(users.systemCollection());
    }

    @Test
    @DisplayName("Should include read-only audit collections")
    void shouldIncludeReadOnlyCollections() {
        Map<String, CollectionDefinition> byName = SystemCollectionDefinitions.byName();
        assertTrue(byName.containsKey("security-audit-logs"));
        assertTrue(byName.containsKey("login-history"));
        assertTrue(byName.containsKey("field-history"));

        // Read-only collections should be marked as such
        CollectionDefinition auditLogs = byName.get("security-audit-logs");
        assertTrue(auditLogs.readOnly());
    }

    @Test
    @DisplayName("Should define fields with correct types in users collection")
    void shouldDefineFieldsWithCorrectTypes() {
        CollectionDefinition users = SystemCollectionDefinitions.users();
        assertNotNull(users.fields());
        assertFalse(users.fields().isEmpty());
        // Users should have fields like email, status, etc.
        assertTrue(users.fields().stream().anyMatch(f -> "email".equals(f.name())));
        assertTrue(users.fields().stream().anyMatch(f -> "status".equals(f.name())));
    }

    @Test
    @DisplayName("Should default layout-fields columnNumber to 0 (0-based columns)")
    void shouldDefaultLayoutFieldColumnNumberToZero() {
        CollectionDefinition layoutFields = SystemCollectionDefinitions.layoutFields();
        FieldDefinition columnNumber = layoutFields.fields().stream()
                .filter(f -> "columnNumber".equals(f.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("columnNumber field not found"));
        assertEquals(0, columnNumber.defaultValue());
    }

    @Test
    @DisplayName("Should make dashboard-components.reportId an optional LOOKUP (KLT-213)")
    void shouldMakeDashboardComponentReportIdOptionalLookup() {
        CollectionDefinition dashboardComponents = SystemCollectionDefinitions.dashboardComponents();
        FieldDefinition reportId = dashboardComponents.getField("reportId");
        assertNotNull(reportId);
        assertEquals(FieldType.LOOKUP, reportId.type());
        assertTrue(reportId.nullable(), "reportId must be nullable — collectionName is a valid alternative target");
    }
}
