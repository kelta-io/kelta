package io.kelta.worker.interceptor;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.FieldMaskingService;
import io.kelta.worker.service.RecordMaskingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CerbosFieldSecurityAdvice Tests (Read-Side)")
class CerbosFieldSecurityAdviceTest {

    @Mock private CerbosAuthorizationService authzService;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private CollectionRegistry collectionRegistry;

    private CerbosFieldSecurityAdvice advice;

    @BeforeEach
    void setUp() {
        // Real masking services over the mocked authz boundary: the unmask
        // decision is still driven by stubbing authzService, matching the
        // existing idiom for the read decision.
        FieldMaskingService fieldMaskingService = new FieldMaskingService();
        RecordMaskingService recordMaskingService =
                new RecordMaskingService(authzService, fieldMaskingService);
        advice = new CerbosFieldSecurityAdvice(authzService, permissionResolver,
                collectionRegistry, recordMaskingService, fieldMaskingService, true);
        // Default: unknown collection — masking is a no-op, preserving the
        // pre-masking behavior for the strip-only tests.
        lenient().when(collectionRegistry.get(anyString())).thenReturn(null);
    }

    private ServletServerHttpRequest createRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-User-Email", "user@example.com");
        request.addHeader("X-User-Profile-Id", "profile-1");
        request.addHeader("X-Cerbos-Scope", "tenant-1");
        lenient().when(permissionResolver.hasIdentity(request)).thenReturn(true);
        lenient().when(permissionResolver.getEmail(request)).thenReturn("user@example.com");
        lenient().when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        lenient().when(permissionResolver.getTenantId(request)).thenReturn("tenant-1");
        return new ServletServerHttpRequest(request);
    }

    @Test
    @DisplayName("Should strip hidden fields from single record response")
    void shouldStripHiddenFieldsFromSingleRecord() {
        var request = createRequest("/api/contacts/123");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name", "email"));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "John");
        attributes.put("email", "john@example.com");
        attributes.put("ssn", "123-45-6789"); // Should be stripped
        Map<String, Object> record = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "123", "attributes", attributes));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> resultBody = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) resultBody.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultAttrs = (Map<String, Object>) resultData.get("attributes");

        assertThat(resultAttrs).containsKeys("name", "email");
        assertThat(resultAttrs).doesNotContainKey("ssn");
    }

    @Test
    @DisplayName("Should strip hidden fields from list response")
    void shouldStripHiddenFieldsFromListResponse() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name"));

        Map<String, Object> attrs1 = new LinkedHashMap<>();
        attrs1.put("name", "John");
        attrs1.put("secret", "hidden");
        Map<String, Object> record1 = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "1", "attributes", attrs1));

        Map<String, Object> attrs2 = new LinkedHashMap<>();
        attrs2.put("name", "Jane");
        attrs2.put("secret", "also hidden");
        Map<String, Object> record2 = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "2", "attributes", attrs2));

        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", List.of(record1, record2)));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(attrs1).containsKey("name");
        assertThat(attrs1).doesNotContainKey("secret");
        assertThat(attrs2).containsKey("name");
        assertThat(attrs2).doesNotContainKey("secret");
    }

    @Test
    @DisplayName("Should not strip system fields")
    void shouldNotStripSystemFields() {
        var request = createRequest("/api/contacts/123");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of()); // Deny everything

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("createdAt", "2026-01-01");
        attributes.put("updatedAt", "2026-01-01");
        attributes.put("createdBy", "admin");
        attributes.put("updatedBy", "admin");
        attributes.put("name", "John"); // Should be stripped
        Map<String, Object> record = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "123", "attributes", attributes));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(attributes).containsKeys("createdAt", "updatedAt", "createdBy", "updatedBy");
        assertThat(attributes).doesNotContainKey("name");
    }

    @Test
    @DisplayName("Should skip metadata paths")
    void shouldSkipMetadataPaths() {
        var request = createRequest("/api/collections/contacts");

        Map<String, Object> body = Map.of("data", Map.of("type", "collections"));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);
        // Should return body unchanged
        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckFieldAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip admin paths")
    void shouldSkipAdminPaths() {
        var request = createRequest("/api/admin/settings");

        Map<String, Object> body = Map.of("data", Map.of("type", "settings"));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);
        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckFieldAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should do nothing when permissions disabled")
    void shouldDoNothingWhenDisabled() {
        FieldMaskingService fieldMaskingService = new FieldMaskingService();
        var disabledAdvice = new CerbosFieldSecurityAdvice(authzService, permissionResolver,
                collectionRegistry, new RecordMaskingService(authzService, fieldMaskingService),
                fieldMaskingService, false);
        assertThat(disabledAdvice.supports(null, null)).isFalse();
    }

    @Test
    @DisplayName("Should handle missing attributes gracefully")
    void shouldHandleMissingAttributes() {
        var request = createRequest("/api/contacts/123");

        Map<String, Object> record = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "123"));
        // No attributes key
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);
        assertThat(result).isNotNull();
        verify(authzService, never()).batchCheckFieldAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should make single batched Cerbos call for list")
    void shouldMakeSingleBatchedCall() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name", "email"));

        Map<String, Object> attrs1 = new LinkedHashMap<>();
        attrs1.put("name", "John");
        attrs1.put("email", "john@example.com");
        Map<String, Object> record1 = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "1", "attributes", attrs1));

        Map<String, Object> attrs2 = new LinkedHashMap<>();
        attrs2.put("name", "Jane");
        attrs2.put("email", "jane@example.com");
        Map<String, Object> record2 = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "2", "attributes", attrs2));

        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", List.of(record1, record2)));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        // Should be called once for all records (batched)
        verify(authzService, times(1)).batchCheckFieldAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should strip a hidden to-one relationship field (lookup/master-detail) from both attributes and relationships")
    void shouldStripHiddenRelationshipField() {
        var request = createRequest("/api/contacts/123");
        // 'name' allowed; the 'account' lookup relationship is denied
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name"));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "John");
        // DynamicCollectionRouter now echoes the lookup id into attributes too.
        attributes.put("account", "acc-1");

        Map<String, Object> relationships = new LinkedHashMap<>();
        relationships.put("account", new LinkedHashMap<>(Map.of(
                "data", new LinkedHashMap<>(Map.of("type", "accounts", "id", "acc-1")))));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "contacts");
        record.put("id", "123");
        record.put("attributes", attributes);
        record.put("relationships", relationships);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(attributes).containsKey("name");
        // The denied lookup is stripped from attributes as well as relationships.
        assertThat(attributes).doesNotContainKey("account");
        // The denied relationship is stripped and the now-empty relationships block removed.
        assertThat(record).doesNotContainKey("relationships");
    }

    @Test
    @DisplayName("Should keep an allowed to-one relationship field")
    void shouldKeepAllowedRelationshipField() {
        var request = createRequest("/api/contacts/123");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name", "account"));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "John");
        Map<String, Object> relationships = new LinkedHashMap<>();
        relationships.put("account", new LinkedHashMap<>(Map.of(
                "data", new LinkedHashMap<>(Map.of("type", "accounts", "id", "acc-1")))));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "contacts");
        record.put("id", "123");
        record.put("attributes", attributes);
        record.put("relationships", relationships);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(relationships).containsKey("account");
    }

    @Test
    @DisplayName("Should not strip has-many inverse relationships (data is a list, not a collection field)")
    void shouldNotStripHasManyInverse() {
        var request = createRequest("/api/accounts/1");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name"));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Acme");
        Map<String, Object> relationships = new LinkedHashMap<>();
        relationships.put("contacts", new LinkedHashMap<>(Map.of(
                "data", List.of(
                        Map.of("type", "contacts", "id", "c1"),
                        Map.of("type", "contacts", "id", "c2")))));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "accounts");
        record.put("id", "1");
        record.put("attributes", attributes);
        record.put("relationships", relationships);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        // The has-many inverse relationship is not a collection field and must survive.
        assertThat(relationships).containsKey("contacts");
    }

    @Test
    @DisplayName("Should strip hidden fields from included resources (by type)")
    void shouldStripIncludedResources() {
        var request = createRequest("/api/contacts/123");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                .thenReturn(List.of("name"));
        when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("accounts"), anyList(), eq("read")))
                .thenReturn(List.of("accountName"));

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", "John");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "contacts");
        record.put("id", "123");
        record.put("attributes", attrs);

        Map<String, Object> incAttrs = new LinkedHashMap<>();
        incAttrs.put("accountName", "Acme");
        incAttrs.put("accountSecret", "top-secret"); // denied — must be stripped
        Map<String, Object> included = new LinkedHashMap<>();
        included.put("type", "accounts");
        included.put("id", "acc-1");
        included.put("attributes", incAttrs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", record);
        body.put("included", List.of(included));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(incAttrs).containsKey("accountName");
        assertThat(incAttrs).doesNotContainKey("accountSecret");
    }

    @Test
    @DisplayName("Should preserve the top-level meta block (pagination meta is not a field-leak vector)")
    void shouldPreserveMetaBlock() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(List.of("name"));

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", "John");
        attrs.put("secret", "x");
        Map<String, Object> record = new LinkedHashMap<>(Map.of(
                "type", "contacts", "id", "1", "attributes", attrs));
        Map<String, Object> meta = new LinkedHashMap<>(Map.of("totalRecords", 1));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(record));
        body.put("meta", meta);

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(attrs).doesNotContainKey("secret");
        assertThat(body).containsKey("meta");
        assertThat(meta).containsEntry("totalRecords", 1);
    }

    @Nested
    @DisplayName("Data masking")
    class DataMasking {

        private FieldDefinition maskedField(String name, FieldType type, String maskType) {
            return new FieldDefinitionBuilder()
                    .name(name)
                    .type(type)
                    .fieldTypeConfig(Map.of(FieldMaskingService.CONFIG_KEY, Map.of("type", maskType)))
                    .build();
        }

        /** contacts with ssn (LAST4) + email (EMAIL) maskable and name unconfigured. */
        private CollectionDefinition maskedContacts() {
            return CollectionDefinition.builder()
                    .name("contacts")
                    .displayName("Contacts")
                    .addField(FieldDefinition.requiredString("name"))
                    .addField(maskedField("ssn", FieldType.STRING, "LAST4"))
                    .addField(maskedField("email", FieldType.EMAIL, "EMAIL"))
                    .build();
        }

        private Map<String, Object> contactRecord(Map<String, Object> attributes) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "contacts");
            record.put("id", "123");
            record.put("attributes", attributes);
            return record;
        }

        @Test
        @DisplayName("Should mask only unmask-denied fields and record them sorted in meta.maskedFields")
        void masksOnlyDeniedFieldsWithMeta() {
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name", "ssn", "email"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of("email"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            attributes.put("email", "john@example.com");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("ssn")).isEqualTo("***-**-6789");
            assertThat(attributes.get("email")).isEqualTo("john@example.com");
            assertThat(attributes.get("name")).isEqualTo("John");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            assertThat(meta).containsEntry("maskedFields", List.of("ssn"));
        }

        @Test
        @DisplayName("Should mask every maskable field when Cerbos allows no unmask (fail-closed), meta sorted")
        void cerbosFailureMasksAllMaskableFields() {
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name", "ssn", "email"));
            // Cerbos unreachable → batched unmask check returns no allowances.
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of());

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            attributes.put("email", "john@example.com");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("ssn")).isEqualTo("***-**-6789");
            assertThat(attributes.get("email")).isEqualTo("j***@example.com");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            // Sorted list, not insertion order.
            assertThat(meta).containsEntry("maskedFields", List.of("email", "ssn"));
        }

        @Test
        @DisplayName("Should return plaintext and no meta when the user may unmask everything")
        void unmaskAllowedLeavesPlaintextAndNoMeta() {
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name", "ssn", "email"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of("ssn", "email"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            attributes.put("email", "john@example.com");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("ssn")).isEqualTo("123-45-6789");
            assertThat(attributes.get("email")).isEqualTo("john@example.com");
            assertThat(record).doesNotContainKey("meta");
        }

        @Test
        @DisplayName("Should make zero unmask Cerbos calls for a collection without masking config")
        void noMaskingConfigMeansNoUnmaskCheck() {
            var request = createRequest("/api/contacts/123");
            CollectionDefinition plain = CollectionDefinition.builder()
                    .name("contacts")
                    .displayName("Contacts")
                    .addField(FieldDefinition.requiredString("name"))
                    .build();
            when(collectionRegistry.get("contacts")).thenReturn(plain);
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("name")).isEqualTo("John");
            assertThat(record).doesNotContainKey("meta");
            verify(authzService, never()).batchCheckFieldAccess(
                    any(), any(), any(), any(), anyList(), eq("unmask"));
        }

        @Test
        @DisplayName("Should strip a HIDDEN masked field, never mask it into existence")
        void hiddenFieldIsStrippedNotMasked() {
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            // ssn is HIDDEN for this profile: read denied.
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes).doesNotContainKey("ssn");
            assertThat(record).doesNotContainKey("meta");
            // Nothing maskable survived the strip — no unmask check at all.
            verify(authzService, never()).batchCheckFieldAccess(
                    any(), any(), any(), any(), anyList(), eq("unmask"));
        }

        @Test
        @DisplayName("Should leave system audit fields untouched by masking")
        void systemAuditFieldsUntouched() {
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("ssn"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of());

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("ssn", "123-45-6789");
            attributes.put("createdAt", "2026-01-01T00:00:00Z");
            attributes.put("updatedAt", "2026-01-02T00:00:00Z");
            attributes.put("createdBy", "admin");
            attributes.put("updatedBy", "admin");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("ssn")).isEqualTo("***-**-6789");
            assertThat(attributes.get("createdAt")).isEqualTo("2026-01-01T00:00:00Z");
            assertThat(attributes.get("updatedAt")).isEqualTo("2026-01-02T00:00:00Z");
            assertThat(attributes.get("createdBy")).isEqualTo("admin");
            assertThat(attributes.get("updatedBy")).isEqualTo("admin");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            assertThat(meta).containsEntry("maskedFields", List.of("ssn"));
        }

        @Test
        @DisplayName("Should mask included[] resources by their own collection's config")
        void masksIncludedResources() {
            var request = createRequest("/api/contacts/123");
            CollectionDefinition accounts = CollectionDefinition.builder()
                    .name("accounts")
                    .displayName("Accounts")
                    .addField(FieldDefinition.requiredString("accountName"))
                    .addField(maskedField("taxId", FieldType.STRING, "LAST4"))
                    .build();
            when(collectionRegistry.get("accounts")).thenReturn(accounts);
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("accounts"), anyList(), eq("read")))
                    .thenReturn(List.of("accountName", "taxId"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("accounts"), anyList(), eq("unmask")))
                    .thenReturn(List.of());

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("name", "John");
            Map<String, Object> record = contactRecord(attrs);

            Map<String, Object> incAttrs = new LinkedHashMap<>();
            incAttrs.put("accountName", "Acme");
            incAttrs.put("taxId", "98-7654321");
            Map<String, Object> included = new LinkedHashMap<>();
            included.put("type", "accounts");
            included.put("id", "acc-1");
            included.put("attributes", incAttrs);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", record);
            body.put("included", List.of(included));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(incAttrs.get("taxId")).isEqualTo("**-***4321");
            assertThat(incAttrs.get("accountName")).isEqualTo("Acme");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) included.get("meta");
            assertThat(meta).containsEntry("maskedFields", List.of("taxId"));
        }

        @Test
        @DisplayName("Sub-resource path masks against the record's `type`, not the parent from the URL")
        void subResourcePathMasksByRecordType() {
            // Request path names the PARENT collection (accounts); the data records are
            // the CHILD type (contacts). Masking must key off `type`, or the child's
            // masked fields leak because the parent has no masking config.
            var request = createRequest("/api/accounts/acc-1/contacts");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name", "ssn"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of());

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", List.of(record)));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            // Child field is masked against the child collection's config.
            assertThat(attributes.get("ssn")).isEqualTo("***-**-6789");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            assertThat(meta).containsEntry("maskedFields", List.of("ssn"));
            // The parent name from the path must never be used as the masking collection.
            verify(collectionRegistry, never()).get("accounts");
        }

        @Test
        @DisplayName("A record without a `type` falls back to the path-derived collection id")
        void missingTypeFallsBackToPathCollection() {
            // No `type` on the record — the advice must fall back to the path segment
            // (contacts) so masking still applies rather than silently skipping.
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name", "ssn"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of());

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", "123"); // no "type" key
            record.put("attributes", attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("ssn")).isEqualTo("***-**-6789");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            assertThat(meta).containsEntry("maskedFields", List.of("ssn"));
        }

        @Test
        @DisplayName("Should keep a null masked value null but still flag it in meta.maskedFields")
        void nullValueStaysNullButFlagged() {
            var request = createRequest("/api/contacts/123");
            when(collectionRegistry.get("contacts")).thenReturn(maskedContacts());
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(List.of("name", "ssn"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("unmask")))
                    .thenReturn(List.of());

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", null);
            Map<String, Object> record = contactRecord(attributes);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("data", record));

            advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(attributes.get("ssn")).isNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            assertThat(meta).containsEntry("maskedFields", List.of("ssn"));
        }
    }
}
