package io.kelta.worker.interceptor;

import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RecordShareAccessService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CerbosRecordAuthorizationAdvice Tests")
class CerbosRecordAuthorizationAdviceTest {

    @Mock private CerbosAuthorizationService authzService;
    @Mock private CerbosPermissionResolver permissionResolver;
    @Mock private RecordShareAccessService recordShareAccessService;
    @Mock private io.kelta.worker.service.RecordRuleIndex recordRuleIndex;

    private CerbosRecordAuthorizationAdvice advice;

    @BeforeEach
    void setUp() {
        // Default: collection has record-variant rules → the batch path (the
        // pre-short-circuit behavior every existing test was written against).
        lenient().when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(true);
        advice = new CerbosRecordAuthorizationAdvice(
                authzService, permissionResolver, recordShareAccessService, recordRuleIndex, true);
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

    @Nested
    @DisplayName("self-scoped controllers")
    class SelfScoped {

        /** Stands in for WatchController/WinController/PushDeviceController. */
        static class MemberController implements SelfScopedController {
            public Map<String, Object> list() {
                return Map.of();
            }
        }

        static class GenericController {
            public Map<String, Object> list() {
                return Map.of();
            }
        }

        private MethodParameter returnTypeOf(Class<?> controller) throws Exception {
            return new MethodParameter(controller.getDeclaredMethod("list"), -1);
        }

        @Test
        @DisplayName("keeps a member's own records — the filter would otherwise empty them")
        void keepsSelfScopedRecords() throws Exception {
            // A portal member holds no record grants, so Cerbos denies every record.
            var request = createRequest("/api/watches");
            Map<String, Object> body = Map.of("data",
                    List.of(Map.of("id", "watch-1", "targetId", "t-1")));

            Object result = advice.beforeBodyWrite(body, returnTypeOf(MemberController.class),
                    MediaType.APPLICATION_JSON, null, request, null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
            assertThat(data).hasSize(1);
            verifyNoInteractions(authzService);
        }

        @Test
        @DisplayName("still filters the generic collection route on the same path")
        void filtersGenericRouteOnSamePath() throws Exception {
            var request = createRequest("/api/watches");
            when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                    .thenReturn(Set.of());
            Map<String, Object> body = Map.of("data",
                    List.of(Map.of("id", "watch-1", "targetId", "t-1")));

            Object result = advice.beforeBodyWrite(body, returnTypeOf(GenericController.class),
                    MediaType.APPLICATION_JSON, null, request, null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
            assertThat(data).isEmpty();
        }
    }

    @Test
    @DisplayName("Should not throw when controller returns immutable Map.of body (regression)")
    void shouldNotThrowOnImmutableBody() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(Set.of("1", "2"));

        Map<String, Object> record1 = Map.of("id", "1", "name", "John");
        Map<String, Object> record2 = Map.of("id", "2", "name", "Jane");
        Map<String, Object> body = Map.of("data", List.of(record1, record2));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> resultBody = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) resultBody.get("data");
        assertThat(data).hasSize(2);
    }

    @Test
    @DisplayName("Should not mutate the input body when filtering list")
    void shouldNotMutateInputBodyForList() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(Set.of("1"));

        Map<String, Object> record1 = Map.of("id", "1", "name", "John");
        Map<String, Object> record2 = Map.of("id", "2", "name", "Jane");
        List<Map<String, Object>> originalData = List.of(record1, record2);
        Map<String, Object> body = Map.of("data", originalData);

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        // Result is a new map, not the input
        assertThat(result).isNotSameAs(body);
        // Input body still references the unfiltered list
        assertThat(body.get("data")).isSameAs(originalData);
        assertThat(((List<?>) body.get("data"))).hasSize(2);
        // Result has the filtered list
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filtered = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("id")).isEqualTo("1");
    }

    @Test
    @DisplayName("Should return new map with data:null when single record denied")
    void shouldReturnNewMapWhenSingleRecordDenied() {
        var request = createRequest("/api/contacts/123");
        when(authzService.checkRecordAccess(any(), any(), any(), any(), eq("123"), anyMap(), eq("read")))
                .thenReturn(false);

        Map<String, Object> record = Map.of("id", "123", "attributes", Map.of("name", "John"));
        Map<String, Object> body = Map.of("data", record);

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isNotSameAs(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultBody = (Map<String, Object>) result;
        assertThat(resultBody).containsKey("data");
        assertThat(resultBody.get("data")).isNull();
        // Input untouched
        assertThat(body.get("data")).isSameAs(record);
    }

    @Test
    @DisplayName("Should return body unchanged when single record allowed")
    void shouldReturnBodyUnchangedWhenSingleRecordAllowed() {
        var request = createRequest("/api/contacts/123");
        when(authzService.checkRecordAccess(any(), any(), any(), any(), eq("123"), anyMap(), eq("read")))
                .thenReturn(true);

        Map<String, Object> record = Map.of("id", "123", "attributes", Map.of("name", "John"));
        Map<String, Object> body = Map.of("data", record);

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
    }

    @Test
    @DisplayName("Should skip /api/telehealth paths — access enforced in-controller (2026-07-12: record check emptied portal appointment lists)")
    void shouldSkipTelehealthPaths() {
        var request = createRequest("/api/telehealth/appointments");
        Map<String, Object> body = Map.of("view", "mine", "data", List.of(
                Map.of("id", "appt-1", "portalUserId", "portal-1", "status", "CONFIRMED")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
        verify(authzService, never()).checkCollectionWideRecordAccess(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should skip /api/api-specs paths")
    void shouldSkipApiSpecsPaths() {
        var request = createRequest("/api/api-specs/library");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1", "name", "spec")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip /api/api-operations paths")
    void shouldSkipApiOperationsPaths() {
        var request = createRequest("/api/api-operations/search");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip /api/credentials paths")
    void shouldSkipCredentialsPaths() {
        var request = createRequest("/api/credentials/types");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1", "name", "Basic Auth")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip /api/connected-apps paths")
    void shouldSkipConnectedAppsPaths() {
        var request = createRequest("/api/connected-apps/app-1/tokens");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip /api/devices path")
    void shouldSkipDevicesPath() {
        var request = createRequest("/api/devices");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip existing metadata paths")
    void shouldSkipExistingMetadataPaths() {
        var request = createRequest("/api/collections/contacts");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip /api/admin and /api/me paths")
    void shouldSkipAdminAndMePaths() {
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        var adminRequest = createRequest("/api/admin/settings");
        assertThat(advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, adminRequest, null)).isSameAs(body);

        var meRequest = createRequest("/api/me/tokens");
        assertThat(advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, meRequest, null)).isSameAs(body);

        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should filter denied records from list response")
    void shouldFilterDeniedRecords() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(Set.of("1")); // Only id=1 allowed

        Map<String, Object> record1 = new LinkedHashMap<>(Map.of("id", "1", "name", "John"));
        Map<String, Object> record2 = new LinkedHashMap<>(Map.of("id", "2", "name", "Jane"));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("data", List.of(record1, record2)));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filtered = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("id")).isEqualTo("1");
    }

    @Test
    @DisplayName("Record share widens a profile-denied record back into a list response")
    void shareWidensDeniedListRecord() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(Set.of("1"));
        when(recordShareAccessService.widen(eq("user@example.com"), eq("contacts"),
                eq(Set.of("2")), eq("read")))
                .thenReturn(Set.of("2"));

        Map<String, Object> record1 = Map.of("id", "1", "name", "John");
        Map<String, Object> record2 = Map.of("id", "2", "name", "Jane");
        Map<String, Object> body = Map.of("data", List.of(record1, record2));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
        assertThat(data).hasSize(2);
    }

    @Test
    @DisplayName("No share → denied records stay filtered")
    void noShareKeepsDenial() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(Set.of("1"));
        when(recordShareAccessService.widen(any(), any(), anySet(), any())).thenReturn(Set.of());

        Map<String, Object> body = Map.of("data", List.of(
                Map.of("id", "1"), Map.of("id", "2")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("id")).isEqualTo("1");
    }

    @Test
    @DisplayName("Record share widens a profile-denied single record")
    void shareWidensDeniedSingleRecord() {
        var request = createRequest("/api/contacts/123");
        when(authzService.checkRecordAccess(any(), any(), any(), any(), eq("123"), anyMap(), eq("read")))
                .thenReturn(false);
        when(recordShareAccessService.widen(eq("user@example.com"), eq("contacts"),
                eq(Set.of("123")), eq("read")))
                .thenReturn(Set.of("123"));

        Map<String, Object> record = Map.of("id", "123", "attributes", Map.of("name", "John"));
        Map<String, Object> body = Map.of("data", record);

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
    }

    @Test
    @DisplayName("Fully-allowed list response never consults the share service")
    void allowedListSkipsShareLookup() {
        var request = createRequest("/api/contacts");
        when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                .thenReturn(Set.of("1", "2"));

        Map<String, Object> body = Map.of("data", List.of(
                Map.of("id", "1"), Map.of("id", "2")));

        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        verify(recordShareAccessService, never()).widen(any(), any(), anySet(), any());
    }

    @Test
    @DisplayName("Should skip when permissions disabled")
    void shouldDoNothingWhenDisabled() {
        var disabledAdvice = new CerbosRecordAuthorizationAdvice(
                authzService, permissionResolver, recordShareAccessService, recordRuleIndex, false);
        assertThat(disabledAdvice.supports(null, null)).isFalse();
    }

    @Test
    @DisplayName("collections without record-variant rules use one collection-wide check, not the batch")
    void shortCircuitsWithoutVariantRules() {
        var request = createRequest("/api/contacts");
        when(recordRuleIndex.hasRecordVariantRules("tenant-1", "contacts")).thenReturn(false);
        when(authzService.checkCollectionWideRecordAccess(
                "user@example.com", "profile-1", "tenant-1", "contacts", "read")).thenReturn(true);

        Map<String, Object> body = Map.of("data", List.of(
                Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) advice.beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat((List<?>) result.get("data")).hasSize(3);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("collection-wide deny filters everything but record shares still widen")
    void shortCircuitDenyStillWidensViaShares() {
        var request = createRequest("/api/contacts");
        when(recordRuleIndex.hasRecordVariantRules("tenant-1", "contacts")).thenReturn(false);
        when(authzService.checkCollectionWideRecordAccess(
                "user@example.com", "profile-1", "tenant-1", "contacts", "read")).thenReturn(false);
        when(recordShareAccessService.widen(eq("user@example.com"), eq("contacts"), anySet(), eq("read")))
                .thenReturn(Set.of("2"));

        Map<String, Object> body = Map.of("data", List.of(
                Map.of("id", "1"), Map.of("id", "2")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) advice.beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON, null, request, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertThat(data).extracting(r -> r.get("id")).containsExactly("2");
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip non-/api/ paths")
    void shouldSkipNonApiPaths() {
        var request = createRequest("/internal/something");
        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should skip when no identity is present")
    void shouldSkipWhenNoIdentity() {
        var request = createRequest("/api/contacts");
        when(permissionResolver.hasIdentity(any())).thenReturn(false);

        Map<String, Object> body = Map.of("data", List.of(Map.of("id", "1")));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should pass through non-Map body unchanged")
    void shouldPassThroughNonMapBody() {
        var request = createRequest("/api/contacts");
        Object body = "plain string";

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should pass through body without data key")
    void shouldPassThroughBodyWithoutData() {
        var request = createRequest("/api/contacts");
        Map<String, Object> body = Map.of("meta", Map.of("count", 0));

        Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

        assertThat(result).isSameAs(body);
        verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), any(), anyList(), any());
    }

    @Nested
    @DisplayName("pagination counts")
    class PaginationCounts {

        /** The router's list envelope: {@code meta} and legacy {@code metadata} share one map. */
        private Map<String, Object> listBody(List<Map<String, Object>> rows, long totalCount, int pageSize) {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("totalCount", totalCount);
            counts.put("currentPage", 1);
            counts.put("pageSize", pageSize);
            counts.put("totalPages", (int) Math.ceil((double) totalCount / pageSize));
            Map<String, Object> body = new HashMap<>();
            body.put("data", rows);
            body.put("meta", counts);
            body.put("metadata", counts);
            return body;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> counts(Object result, String key) {
            return (Map<String, Object>) ((Map<String, Object>) result).get(key);
        }

        @Test
        @DisplayName("reduces totalCount/totalPages in meta and metadata by the dropped rows")
        void reducesCountsByDroppedRows() {
            var request = createRequest("/api/users");
            when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                    .thenReturn(Set.of("1"));
            Map<String, Object> body = listBody(List.of(
                    Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")), 3, 2);

            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(counts(result, "metadata")).containsEntry("totalCount", 1L).containsEntry("totalPages", 1);
            assertThat(counts(result, "meta")).isSameAs(counts(result, "metadata"));
        }

        @Test
        @DisplayName("every row dropped leaves totalCount 0")
        void everyRowDroppedIsZero() {
            var request = createRequest("/api/users");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("users"), eq("read")))
                    .thenReturn(false);
            Map<String, Object> body = listBody(List.of(
                    Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")), 3, 20);

            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat((List<?>) ((Map<?, ?>) result).get("data")).isEmpty();
            assertThat(counts(result, "metadata")).containsEntry("totalCount", 0L).containsEntry("totalPages", 0);
            assertThat(counts(result, "meta")).containsEntry("totalCount", 0L);
        }

        @Test
        @DisplayName("leaves counts untouched when nothing is dropped")
        void untouchedWhenNothingDropped() {
            var request = createRequest("/api/users");
            when(authzService.batchCheckRecordAccess(any(), any(), any(), any(), anyList(), eq("read")))
                    .thenReturn(Set.of("1", "2"));
            Map<String, Object> body = listBody(List.of(Map.of("id", "1"), Map.of("id", "2")), 7, 2);

            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(counts(result, "metadata")).containsEntry("totalCount", 7L).containsEntry("totalPages", 4);
        }
    }
}
