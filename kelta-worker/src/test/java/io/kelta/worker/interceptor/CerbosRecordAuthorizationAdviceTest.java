package io.kelta.worker.interceptor;

import io.kelta.worker.controller.BulkOperationsController;
import io.kelta.worker.controller.ChatController;
import io.kelta.worker.controller.DataExportController;
import io.kelta.worker.controller.EmailLogController;
import io.kelta.worker.controller.GovernorLimitsController;
import io.kelta.worker.controller.MailboxAdminController;
import io.kelta.worker.controller.MailboxController;
import io.kelta.worker.controller.ModuleController;
import io.kelta.worker.controller.ModuleSigningKeyController;
import io.kelta.worker.controller.PackageController;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RecordShareAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;
import java.util.stream.Stream;

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

    @Nested
    @DisplayName("permission-gated controllers")
    class PermissionGated {

        /** Stands in for PromotionController/EnvironmentController. */
        static class SandboxController implements PermissionGatedController {
            public Map<String, Object> execute() {
                return Map.of();
            }
        }

        static class GenericController {
            public Map<String, Object> execute() {
                return Map.of();
            }
        }

        private MethodParameter returnTypeOf(Class<?> controller) throws Exception {
            return new MethodParameter(controller.getDeclaredMethod("execute"), -1);
        }

        private ServletServerHttpRequest promotionExecute() {
            var request = createRequest("/api/promotions/promo-1/execute");
            ((MockHttpServletRequest) request.getServletRequest()).setMethod("POST");
            return request;
        }

        private Map<String, Object> promotion() {
            return Map.of("data", Map.of("type", "promotions", "id", "promo-1",
                    "attributes", Map.of("status", "EXECUTING")));
        }

        @Test
        @DisplayName("keeps the resource for a caller without data-wide grants")
        void keepsGatedResource() throws Exception {
            Map<String, Object> body = promotion();

            Object result = advice.beforeBodyWrite(body, returnTypeOf(SandboxController.class),
                    MediaType.APPLICATION_JSON, null, promotionExecute(), null);

            assertThat(result).isSameAs(body);
            verifyNoInteractions(authzService);
        }

        @Test
        @DisplayName("a handler without the marker on the same path is still checked")
        void genericHandlerStillChecked() throws Exception {
            // "promotions" is no collection: only MODIFY_ALL_DATA passes a create, so the
            // advice nulls the resource after the promotion already started.
            Object result = advice.beforeBodyWrite(promotion(), returnTypeOf(GenericController.class),
                    MediaType.APPLICATION_JSON, null, promotionExecute(), null);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultBody = (Map<String, Object>) result;
            assertThat(resultBody).containsEntry("data", null);
        }

        @Test
        @DisplayName("keeps package history for a caller without a grant on the packages collection")
        void keepsPackageHistory() throws Exception {
            // "packages" is a system collection no seeded profile holds an object permission on,
            // so a CUSTOMIZE_APPLICATION profile without VIEW_ALL_DATA (Solution Manager) had its
            // history emptied by the record check after the controller's own gate admitted it.
            var request = createRequest("/api/packages/history");
            Map<String, Object> body = Map.of("data", List.of(Map.of("type", "packages",
                    "id", "pkg-1", "attributes", Map.of("name", "acme", "status", "success"))));
            MethodParameter getHistory = new MethodParameter(
                    PackageController.class.getDeclaredMethod("getHistory", HttpServletRequest.class), -1);

            Object result = advice.beforeBodyWrite(body, getHistory,
                    MediaType.APPLICATION_JSON, null, request, null);

            assertThat(result).isSameAs(body);
            verifyNoInteractions(authzService);
        }
    }

    /**
     * The real handlers whose bodies are not records of the path's first segment. Each was emptied
     * or nulled for a caller without VIEW_ALL_DATA / MODIFY_ALL_DATA — often after its write had
     * run (a support mailbox's inbound secret is shown exactly once).
     */
    @Nested
    @DisplayName("controllers that authorize their own responses")
    class OwnAuthorization {

        static Stream<Arguments> handlers() {
            return Stream.of(
                    Arguments.of(BulkOperationsController.class, "listJobs", "GET", "/api/bulk-jobs"),
                    Arguments.of(BulkOperationsController.class, "createJob", "POST", "/api/bulk-jobs"),
                    Arguments.of(DataExportController.class, "createExport", "POST", "/api/data-exports"),
                    Arguments.of(DataExportController.class, "cancelExport", "POST", "/api/data-exports/x-1/cancel"),
                    Arguments.of(GovernorLimitsController.class, "getStatus", "GET", "/api/governor-limits"),
                    Arguments.of(GovernorLimitsController.class, "updateLimits", "PUT", "/api/governor-limits"),
                    Arguments.of(MailboxAdminController.class, "create", "POST", "/api/support/mailboxes"),
                    Arguments.of(MailboxAdminController.class, "rotateSecret", "POST", "/api/support/mailboxes/mb-1/rotate-secret"),
                    Arguments.of(ModuleController.class, "listModules", "GET", "/api/modules"),
                    Arguments.of(ModuleController.class, "enableModule", "POST", "/api/modules/m-1/enable"),
                    Arguments.of(ModuleSigningKeyController.class, "listKeys", "GET", "/api/modules/signing-keys"),
                    Arguments.of(ModuleSigningKeyController.class, "addKey", "POST", "/api/modules/signing-keys"),
                    Arguments.of(ChatController.class, "list", "GET", "/api/chat/conversations"),
                    Arguments.of(ChatController.class, "messages", "GET", "/api/chat/conversations/c-1/messages"),
                    Arguments.of(MailboxController.class, "listThreads", "GET", "/api/support/threads"),
                    Arguments.of(MailboxController.class, "claim", "POST", "/api/support/threads/t-1/claim"),
                    Arguments.of(EmailLogController.class, "listLogsByRecord", "GET", "/api/email/logs"));
        }

        private MethodParameter handler(Class<?> controller, String name) {
            return Arrays.stream(controller.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst()
                    .map(m -> new MethodParameter(m, -1))
                    .orElseThrow(() -> new AssertionError(controller.getSimpleName() + "." + name + " not found"));
        }

        @ParameterizedTest(name = "{0}.{1}")
        @MethodSource("handlers")
        @DisplayName("keeps the body for a caller without data-wide grants")
        void keepsBody(Class<?> controller, String handler, String method, String path) {
            var request = createRequest(path);
            ((MockHttpServletRequest) request.getServletRequest()).setMethod(method);
            Map<String, Object> resource = Map.of("type", "resource", "id", "id-1",
                    "attributes", Map.of("name", "n"));
            Map<String, Object> body = "GET".equals(method)
                    ? Map.of("data", List.of(resource))
                    : Map.of("data", resource);

            Object result = advice.beforeBodyWrite(body, handler(controller, handler),
                    MediaType.APPLICATION_JSON, null, request, null);

            assertThat(result).isSameAs(body);
            verifyNoInteractions(authzService);
        }
    }

    @Nested
    @DisplayName("derived-data controllers")
    class DerivedData {

        /** Stands in for DashboardDataController/ReportExecutionController. */
        static class WidgetController implements DerivedDataController {
            public Map<String, Object> execute() {
                return Map.of();
            }
        }

        static class GenericController {
            public Map<String, Object> execute() {
                return Map.of();
            }
        }

        private MethodParameter returnTypeOf(Class<?> controller) throws Exception {
            return new MethodParameter(controller.getDeclaredMethod("execute"), -1);
        }

        private ServletServerHttpRequest widgetDataPost() {
            var request = createRequest("/api/dashboards/dash-1/components/comp-1/data");
            ((MockHttpServletRequest) request.getServletRequest()).setMethod("POST");
            return request;
        }

        private Map<String, Object> widgetData() {
            return Map.of("data", Map.of("type", "widget-data", "id", "comp-1",
                    "attributes", Map.of("componentId", "comp-1", "data", Map.of("series", List.of()))));
        }

        @Test
        @DisplayName("keeps a widget result for a caller without create on dashboards")
        void keepsDerivedResult() throws Exception {
            Map<String, Object> body = widgetData();

            Object result = advice.beforeBodyWrite(body, returnTypeOf(WidgetController.class),
                    MediaType.APPLICATION_JSON, null, widgetDataPost(), null);

            assertThat(result).isSameAs(body);
            verifyNoInteractions(authzService);
        }

        @Test
        @DisplayName("a non-derived handler on the same POST path is still checked as a create")
        void genericHandlerStillChecked() throws Exception {
            // No create grant on dashboards: the advice nulls the single resource, which is what
            // emptied every widget for read-only analytics callers before the marker existed.
            Object result = advice.beforeBodyWrite(widgetData(), returnTypeOf(GenericController.class),
                    MediaType.APPLICATION_JSON, null, widgetDataPost(), null);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultBody = (Map<String, Object>) result;
            assertThat(resultBody).containsEntry("data", null);
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

    @Nested
    @DisplayName("isRecordReadable (single-record decision for handlers that return no record)")
    class IsRecordReadable {

        private jakarta.servlet.http.HttpServletRequest servletRequest() {
            return createRequest("/api/photos/download/k").getServletRequest();
        }

        @Test
        @DisplayName("record-variant rules: checks the record with its field values as attributes")
        void recordVariantChecksRecord() {
            var request = servletRequest();
            when(authzService.checkRecordAccess(eq("user@example.com"), eq("profile-1"), eq("tenant-1"),
                    eq("photos"), eq("rec-1"), eq(Map.of("storageKey", "t/photos/u/a.jpg")), eq("read")))
                    .thenReturn(true);

            assertThat(advice.isRecordReadable(request, "photos",
                    Map.of("id", "rec-1", "storageKey", "t/photos/u/a.jpg"))).isTrue();
        }

        @Test
        @DisplayName("a denied record is unreadable unless a manual share widens it")
        void deniedUnlessShared() {
            var request = servletRequest();
            when(authzService.checkRecordAccess(any(), any(), any(), any(), any(), any(), eq("read")))
                    .thenReturn(false);
            when(recordShareAccessService.widen(any(), eq("photos"), eq(Set.of("rec-1")), eq("read")))
                    .thenReturn(Set.of());
            assertThat(advice.isRecordReadable(request, "photos", Map.of("id", "rec-1"))).isFalse();

            when(recordShareAccessService.widen(any(), eq("photos"), eq(Set.of("rec-1")), eq("read")))
                    .thenReturn(Set.of("rec-1"));
            assertThat(advice.isRecordReadable(request, "photos", Map.of("id", "rec-1"))).isTrue();
        }

        @Test
        @DisplayName("record-invariant policy: one collection-wide check decides")
        void collectionWide() {
            var request = servletRequest();
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("photos"), eq("read")))
                    .thenReturn(true);

            assertThat(advice.isRecordReadable(request, "photos", Map.of("id", "rec-1"))).isTrue();
            verify(authzService, never()).checkRecordAccess(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a record without an id is never readable (fail closed)")
        void noIdFailsClosed() {
            assertThat(advice.isRecordReadable(servletRequest(), "photos", Map.of("title", "x"))).isFalse();
        }

        @Test
        @DisplayName("no Cerbos identity, or permissions disabled: allowed, as the advice leaves such bodies alone")
        void noIdentityOrDisabled() {
            MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/photos/download/k");
            when(permissionResolver.hasIdentity(anonymous)).thenReturn(false);
            assertThat(advice.isRecordReadable(anonymous, "photos", Map.of("id", "rec-1"))).isTrue();

            CerbosRecordAuthorizationAdvice disabled = new CerbosRecordAuthorizationAdvice(
                    authzService, permissionResolver, recordShareAccessService, recordRuleIndex, false);
            assertThat(disabled.isRecordReadable(servletRequest(), "photos", Map.of("id", "rec-1"))).isTrue();
            verifyNoInteractions(authzService);
        }
    }

    @Nested
    @DisplayName("isRecordEditable (edit decision for handlers that write beside a record)")
    class IsRecordEditable {

        private jakarta.servlet.http.HttpServletRequest servletRequest() {
            return createRequest("/api/attachments/upload-url").getServletRequest();
        }

        @Test
        @DisplayName("checks the edit action on the record, with its field values as attributes")
        void checksEditAction() {
            var request = servletRequest();
            when(authzService.checkRecordAccess(eq("user@example.com"), eq("profile-1"), eq("tenant-1"),
                    eq("accounts"), eq("rec-1"), eq(Map.of("ownerId", "u-1")), eq("edit")))
                    .thenReturn(true);

            assertThat(advice.isRecordEditable(request, "accounts", Map.of("id", "rec-1", "ownerId", "u-1"))).isTrue();
            verify(authzService, never()).checkRecordAccess(any(), any(), any(), any(), any(), any(), eq("read"));
        }

        @Test
        @DisplayName("a READ share does not make a record editable; an EDIT share does")
        void onlyEditSharesWiden() {
            var request = servletRequest();
            when(authzService.checkRecordAccess(any(), any(), any(), any(), any(), any(), eq("edit")))
                    .thenReturn(false);
            when(recordShareAccessService.widen(any(), eq("accounts"), eq(Set.of("rec-1")), eq("edit")))
                    .thenReturn(Set.of());
            assertThat(advice.isRecordEditable(request, "accounts", Map.of("id", "rec-1"))).isFalse();

            when(recordShareAccessService.widen(any(), eq("accounts"), eq(Set.of("rec-1")), eq("edit")))
                    .thenReturn(Set.of("rec-1"));
            assertThat(advice.isRecordEditable(request, "accounts", Map.of("id", "rec-1"))).isTrue();
        }

        @Test
        @DisplayName("a record without an id is never editable (fail closed)")
        void noIdFailsClosed() {
            assertThat(advice.isRecordEditable(servletRequest(), "accounts", Map.of("name", "x"))).isFalse();
        }
    }

    @Nested
    @DisplayName("child-resource routes")
    class ChildResourceRoutes {

        private ServletServerHttpRequest matched(String method, String path, Map<String, String> uriVariables) {
            ServletServerHttpRequest request = createRequest(path);
            MockHttpServletRequest servlet = (MockHttpServletRequest) request.getServletRequest();
            servlet.setMethod(method);
            servlet.setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    uriVariables);
            return request;
        }

        private Map<String, Object> listOf(String... ids) {
            List<Map<String, Object>> data = new ArrayList<>();
            for (String id : ids) {
                data.add(Map.of("id", id, "attributes", Map.of()));
            }
            return Map.of("data", data);
        }

        @Test
        @DisplayName("A child list is checked against the child collection, not the parent")
        void childListUsesChildCollection() {
            var request = matched("GET", "/api/accounts/a1/contacts",
                    Map.of("parentName", "accounts", "parentId", "a1", "childName", "contacts"));
            when(authzService.batchCheckRecordAccess(any(), any(), any(), eq("contacts"), anyList(), eq("read")))
                    .thenReturn(Set.of("c1"));

            Object result = advice.beforeBodyWrite(listOf("c1", "c2"), null, MediaType.APPLICATION_JSON, null,
                    request, null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<String, Object>) result).get("data");
            assertThat(data).extracting(r -> r.get("id")).containsExactly("c1");
            verify(authzService, never()).batchCheckRecordAccess(any(), any(), any(), eq("accounts"), anyList(), any());
        }

        @Test
        @DisplayName("A child record write response is checked with the child's edit rules")
        void childWriteUsesChildCollectionAndAction() {
            var request = matched("PATCH", "/api/accounts/a1/contacts/c1",
                    Map.of("parentName", "accounts", "parentId", "a1", "childName", "contacts", "childId", "c1"));
            when(authzService.checkRecordAccess(any(), any(), any(), eq("contacts"), eq("c1"), anyMap(), eq("edit")))
                    .thenReturn(false);

            Map<String, Object> body = Map.of("data", Map.of("id", "c1", "attributes", Map.of("name", "x")));
            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(((Map<?, ?>) result).get("data")).isNull();
            verify(authzService, never()).checkRecordAccess(any(), any(), any(), eq("accounts"), any(), anyMap(), any());
        }

        @Test
        @DisplayName("A dedicated controller at child depth keeps the first segment's collection")
        void dedicatedControllerAtChildDepthKeepsFirstSegment() {
            var request = matched("POST", "/api/attachments/a1/finalize", Map.of("id", "a1"));
            when(authzService.checkRecordAccess(any(), any(), any(), eq("attachments"), eq("a1"), anyMap(), eq("create")))
                    .thenReturn(true);

            Map<String, Object> body = Map.of("data", Map.of("id", "a1", "attributes", Map.of()));
            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(result).isSameAs(body);
        }
    }

    @Nested
    @DisplayName("included resources")
    class IncludedResources {

        private Map<String, Object> resource(String type, String id, Map<String, Object> attributes) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", type);
            resource.put("id", id);
            resource.put("attributes", attributes);
            return resource;
        }

        private Map<String, Object> listWithIncluded(List<Map<String, Object>> included) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", List.of(resource("orders", "o1", Map.of("customer", "c1"))));
            body.put("included", included);
            return body;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> included(Object result) {
            return (List<Map<String, Object>>) ((Map<String, Object>) result).get("included");
        }

        @Test
        @DisplayName("checks each included collection with its own collection-wide read decision")
        void includedCollectionWideDecisionPerType() {
            var request = createRequest("/api/orders");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(true);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("customers"), eq("read")))
                    .thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("products"), eq("read")))
                    .thenReturn(true);

            Object result = advice.beforeBodyWrite(listWithIncluded(List.of(
                            resource("customers", "c1", Map.of("name", "Acme")),
                            resource("customers", "c2", Map.of("name", "Beta")),
                            resource("products", "p1", Map.of("sku", "X")))),
                    null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(included(result)).extracting(r -> r.get("id")).containsExactly("p1");
            // One decision per included type, not per included resource.
            verify(authzService, times(1)).checkCollectionWideRecordAccess(
                    any(), any(), any(), eq("customers"), eq("read"));
            verify(authzService, times(1)).checkCollectionWideRecordAccess(
                    any(), any(), any(), eq("products"), eq("read"));
        }

        @Test
        @DisplayName("checks included records of a record-variant collection in one batch")
        void includedRecordVariantBatch() {
            var request = createRequest("/api/orders");
            when(recordRuleIndex.hasRecordVariantRules(any(), eq("orders"))).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(true);
            when(recordRuleIndex.hasRecordVariantRules(any(), eq("customers"))).thenReturn(true);
            when(authzService.batchCheckRecordAccess(any(), any(), any(), eq("customers"), anyList(), eq("read")))
                    .thenReturn(Set.of("c2"));

            Object result = advice.beforeBodyWrite(listWithIncluded(List.of(
                            resource("customers", "c1", Map.of("tier", "restricted")),
                            resource("customers", "c2", Map.of("tier", "open")))),
                    null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(included(result)).extracting(r -> r.get("id")).containsExactly("c2");
            verify(authzService, times(1)).batchCheckRecordAccess(
                    any(), any(), any(), eq("customers"),
                    argThat(records -> records.size() == 2), eq("read"));
        }

        @Test
        @DisplayName("a manual record share widens an included record the profile denied")
        void includedShareWidening() {
            var request = createRequest("/api/orders");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(true);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("customers"), eq("read")))
                    .thenReturn(false);
            when(recordShareAccessService.widen(eq("user@example.com"), eq("customers"), eq(Set.of("c1", "c2")),
                    eq("read"))).thenReturn(Set.of("c1"));

            Object result = advice.beforeBodyWrite(listWithIncluded(List.of(
                            resource("customers", "c1", Map.of()),
                            resource("customers", "c2", Map.of()))),
                    null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(included(result)).extracting(r -> r.get("id")).containsExactly("c1");
        }

        @Test
        @DisplayName("removes the included member when nothing in it is readable")
        void noReadableIncludedRemovesMember() {
            var request = createRequest("/api/orders");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(true);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("customers"), eq("read")))
                    .thenReturn(false);

            Object result = advice.beforeBodyWrite(listWithIncluded(List.of(resource("customers", "c1", Map.of()))),
                    null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(((Map<?, ?>) result).containsKey("included")).isFalse();
            assertThat((List<?>) ((Map<?, ?>) result).get("data")).hasSize(1);
        }

        @Test
        @DisplayName("filters the included resources of a readable single record")
        void singleRecordIncludedFiltered() {
            var request = createRequest("/api/orders/o1");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(true);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("customers"), eq("read")))
                    .thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("products"), eq("read")))
                    .thenReturn(true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", resource("orders", "o1", Map.of("customer", "c1")));
            body.put("included", List.of(resource("customers", "c1", Map.of()), resource("products", "p1", Map.of())));

            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(((Map<?, ?>) result).get("data")).isNotNull();
            assertThat(included(result)).extracting(r -> r.get("id")).containsExactly("p1");
            // The relationship linkage on the primary record is left as it is.
            assertThat(((Map<?, ?>) ((Map<?, ?>) result).get("data")).get("attributes"))
                    .isEqualTo(Map.of("customer", "c1"));
        }

        @Test
        @DisplayName("a denied single record carries no included resources")
        void deniedSingleRecordDropsIncluded() {
            var request = createRequest("/api/orders/o1");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(false);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", resource("orders", "o1", Map.of()));
            body.put("included", List.of(resource("order-lines", "l1", Map.of())));

            Object result = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(((Map<?, ?>) result).get("data")).isNull();
            assertThat(((Map<?, ?>) result).containsKey("included")).isFalse();
            verify(authzService, never()).checkCollectionWideRecordAccess(
                    any(), any(), any(), eq("order-lines"), any());
        }

        @Test
        @DisplayName("keeps included resources of a type whose direct path the advice does not filter")
        void metadataTypeIncludedKept() {
            var request = createRequest("/api/orders");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("orders"), eq("read")))
                    .thenReturn(true);

            Object result = advice.beforeBodyWrite(listWithIncluded(List.of(resource("profiles", "pr1", Map.of()))),
                    null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(included(result)).extracting(r -> r.get("id")).containsExactly("pr1");
            verify(authzService, never()).checkCollectionWideRecordAccess(any(), any(), any(), eq("profiles"), any());
        }

        @Test
        @DisplayName("drops an included resource without a type or id")
        void unattributableIncludedDropped() {
            var request = createRequest("/api/orders");
            when(recordRuleIndex.hasRecordVariantRules(any(), any())).thenReturn(false);
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), any(), eq("read")))
                    .thenReturn(true);

            Map<String, Object> typeless = new LinkedHashMap<>();
            typeless.put("id", "x1");
            Map<String, Object> idless = new LinkedHashMap<>();
            idless.put("type", "products");

            Object result = advice.beforeBodyWrite(listWithIncluded(List.of(
                            typeless, idless, resource("products", "p1", Map.of()))),
                    null, MediaType.APPLICATION_JSON, null, request, null);

            assertThat(included(result)).extracting(r -> r.get("id")).containsExactly("p1");
        }
    }
}
