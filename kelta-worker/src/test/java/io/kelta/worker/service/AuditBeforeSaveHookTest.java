package io.kelta.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AuditBeforeSaveHook}.
 */
@DisplayName("AuditBeforeSaveHook")
class AuditBeforeSaveHookTest {

    private SetupAuditService auditService;
    private AuditBeforeSaveHook hook;

    @BeforeEach
    void setUp() {
        auditService = mock(SetupAuditService.class);
        hook = new AuditBeforeSaveHook(auditService);
    }

    @Test
    @DisplayName("Should return wildcard collection name")
    void shouldReturnWildcardCollectionName() {
        assertEquals("*", hook.getCollectionName());
    }

    @Test
    @DisplayName("Should return high order value (runs last)")
    void shouldReturnHighOrderValue() {
        assertEquals(1000, hook.getOrder());
    }

    @Test
    @DisplayName("Should audit create on setup collection")
    void shouldAuditCreateOnSetupCollection() {
        Map<String, Object> data = Map.of(
                "name", "accounts", "apiName", "accounts",
                "id", "coll-123", "createdBy", "user-1");

        hook.afterCreate("collections", data, "tenant-1");

        verify(auditService).log(
                eq("tenant-1"), eq("user-1"), eq("CREATED"), eq("Schema"),
                eq("collection"), eq("coll-123"), eq("accounts"),
                isNull(), contains("accounts"));
    }

    @Test
    @DisplayName("Should audit update on setup collection")
    void shouldAuditUpdateOnSetupCollection() {
        Map<String, Object> data = Map.of("label", "Updated Label", "updatedBy", "user-1");

        hook.afterUpdate("fields", "field-456", data, Map.of(), "tenant-1");

        verify(auditService).log(
                eq("tenant-1"), eq("user-1"), eq("UPDATED"), eq("Schema"),
                eq("field"), eq("field-456"), isNull(),
                isNull(), contains("Updated Label"));
    }

    @Test
    @DisplayName("Should keep server-only fields out of the audit trail on create and update")
    void shouldOmitServerOnlyFields() {
        Map<String, Object> data = Map.of(
                "name", "corp-idp", "id", "idp-1", "createdBy", "user-1",
                "clientSecretEnc", "enc:v1:ciphertext");

        hook.afterCreate("oidc-providers", data, "tenant-1");
        hook.afterUpdate("oidc-providers", "idp-1", data, Map.of(), "tenant-1");

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService, times(2)).log(
                eq("tenant-1"), eq("user-1"), anyString(), eq("OIDC"),
                eq("oidc-provider"), eq("idp-1"), any(), isNull(), json.capture());
        for (String written : json.getAllValues()) {
            assertTrue(written.contains("corp-idp"), written);
            assertFalse(written.contains("clientSecretEnc"), written);
            assertFalse(written.contains("ciphertext"), written);
        }
    }

    @Test
    @DisplayName("Should audit delete on setup collection")
    void shouldAuditDeleteOnSetupCollection() {
        hook.afterDelete("profiles", "prof-789", "tenant-1");

        verify(auditService).log(
                eq("tenant-1"), isNull(), eq("DELETED"), eq("Profiles"),
                eq("profile"), eq("prof-789"), isNull(),
                isNull(), isNull());
    }

    @Test
    @DisplayName("Should skip non-setup collection on create")
    void shouldSkipNonSetupCollection() {
        hook.afterCreate("my-custom-collection",
                Map.of("name", "test", "id", "rec-1"), "tenant-1");

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Should skip non-setup collection on update")
    void shouldSkipNonSetupCollectionOnUpdate() {
        hook.afterUpdate("orders", "order-1",
                Map.of("status", "SHIPPED"), Map.of(), "tenant-1");

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Should skip non-setup collection on delete")
    void shouldSkipNonSetupCollectionOnDelete() {
        hook.afterDelete("invoices", "inv-1", "tenant-1");

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Should map profile permission collections")
    void shouldMapProfilePermissionCollections() {
        hook.afterCreate("profile-system-permissions",
                Map.of("name", "VIEW_ALL", "id", "psp-1", "createdBy", "u1"), "t1");

        verify(auditService).log(eq("t1"), eq("u1"), eq("CREATED"), eq("Profiles"),
                eq("system-permissions"), eq("psp-1"), eq("VIEW_ALL"),
                isNull(), any());
    }

    @Test
    @DisplayName("Should map users collection")
    void shouldMapUsersCollection() {
        hook.afterUpdate("users", "u2",
                Map.of("email", "new@example.com", "updatedBy", "u1"), Map.of(), "t1");

        verify(auditService).log(eq("t1"), eq("u1"), eq("UPDATED"), eq("Users"),
                eq("user"), eq("u2"), isNull(),
                isNull(), any());
    }

    @Test
    @DisplayName("Should map flows collection")
    void shouldMapFlowsCollection() {
        hook.afterCreate("flows",
                Map.of("name", "My Flow", "id", "flow-1", "createdBy", "u1"), "t1");

        verify(auditService).log(eq("t1"), eq("u1"), eq("CREATED"), eq("Flows"),
                eq("flow"), eq("flow-1"), eq("My Flow"),
                isNull(), any());
    }

    @Test
    @DisplayName("Should map OIDC providers")
    void shouldMapOidcProviders() {
        hook.afterDelete("oidc-providers", "oidc-1", "t1");

        verify(auditService).log(eq("t1"), isNull(), eq("DELETED"), eq("OIDC"),
                eq("oidc-provider"), eq("oidc-1"), isNull(),
                isNull(), isNull());
    }

    @Test
    @DisplayName("Should resolve entity type for junction table")
    void shouldResolveEntityTypeForJunctionTable() {
        assertEquals("system-permissions",
                AuditBeforeSaveHook.resolveEntityType("profile-system-permissions"));
        assertEquals("object-permissions",
                AuditBeforeSaveHook.resolveEntityType("profile-object-permissions"));
    }

    @Test
    @DisplayName("Should resolve entity type for regular collection")
    void shouldResolveEntityTypeForRegularCollection() {
        assertEquals("collection", AuditBeforeSaveHook.resolveEntityType("collections"));
        assertEquals("field", AuditBeforeSaveHook.resolveEntityType("fields"));
        assertEquals("profile", AuditBeforeSaveHook.resolveEntityType("profiles"));
        assertEquals("user", AuditBeforeSaveHook.resolveEntityType("users"));
        assertEquals("flow", AuditBeforeSaveHook.resolveEntityType("flows"));
    }

    @Test
    @DisplayName("Should extract entity name from name field")
    void shouldExtractEntityNameFromNameField() {
        Map<String, Object> data = Map.of("name", "Test Entity", "id", "c1");

        hook.afterCreate("collections", data, "t1");

        verify(auditService).log(any(), any(), any(), any(), any(),
                any(), eq("Test Entity"), any(), any());
    }

    @Test
    @DisplayName("Should extract entity name from label field")
    void shouldExtractEntityNameFromLabelField() {
        Map<String, Object> data = Map.of("label", "My Label", "id", "f1");

        hook.afterCreate("fields", data, "t1");

        verify(auditService).log(any(), any(), any(), any(), any(),
                any(), eq("My Label"), any(), any());
    }

    @Test
    @DisplayName("Should extract entity name from email field")
    void shouldExtractEntityNameFromEmailField() {
        Map<String, Object> data = Map.of("email", "alice@example.com", "id", "u2");

        hook.afterCreate("users", data, "t1");

        verify(auditService).log(any(), any(), any(), any(), any(),
                any(), eq("alice@example.com"), any(), any());
    }

    @Test
    @DisplayName("Should extract userId from createdBy field on create")
    void shouldExtractUserIdFromCreatedByOnCreate() {
        Map<String, Object> data = Map.of(
                "name", "Test", "id", "c1", "createdBy", "user-uuid-123");

        hook.afterCreate("collections", data, "t1");

        verify(auditService).log(eq("t1"), eq("user-uuid-123"), eq("CREATED"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should extract userId from updatedBy field on update")
    void shouldExtractUserIdFromUpdatedByOnUpdate() {
        Map<String, Object> data = Map.of("updatedBy", "user-uuid-456");

        hook.afterUpdate("collections", "c1", data, Map.of(), "t1");

        verify(auditService).log(eq("t1"), eq("user-uuid-456"), eq("UPDATED"),
                any(), any(), any(), any(), any(), any());
    }
}
