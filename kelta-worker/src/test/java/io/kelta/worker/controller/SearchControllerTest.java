package io.kelta.worker.controller;

import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.TestFixtures;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RecordRuleIndex;
import io.kelta.worker.service.RecordShareAccessService;
import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.analytics.AnalyticsCaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SearchController")
class SearchControllerTest {

    private SearchIndexService searchIndexService;
    private AnalyticsCaptureService analyticsCaptureService;
    private CerbosAuthorizationService authzService;
    private CerbosPermissionResolver permissionResolver;
    private RecordShareAccessService recordShareAccessService;
    private RecordRuleIndex recordRuleIndex;
    private CollectionRegistry collectionRegistry;
    private QueryEngine queryEngine;
    private MockHttpServletRequest request;
    private SearchController controller;

    @BeforeEach
    void setUp() {
        searchIndexService = mock(SearchIndexService.class);
        analyticsCaptureService = mock(AnalyticsCaptureService.class);
        authzService = mock(CerbosAuthorizationService.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        recordShareAccessService = mock(RecordShareAccessService.class);
        recordRuleIndex = mock(RecordRuleIndex.class);
        collectionRegistry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);

        request = new MockHttpServletRequest("GET", "/api/_search");
        when(permissionResolver.hasIdentity(request)).thenReturn(true);
        when(permissionResolver.getEmail(request)).thenReturn("user@example.com");
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(permissionResolver.getTenantId(request)).thenReturn("tenant-1");
        for (String name : List.of("products", "accounts", "invoices")) {
            when(collectionRegistry.get(name)).thenReturn(TestFixtures.collectionDefinition(name));
        }
        // By default every collection is readable collection-wide.
        when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), any(), eq("read")))
                .thenReturn(true);
        when(recordShareAccessService.widen(any(), any(), any(), any())).thenReturn(Set.of());

        CerbosRecordAuthorizationAdvice recordAuthorization = new CerbosRecordAuthorizationAdvice(
                authzService, permissionResolver, recordShareAccessService, recordRuleIndex,
                collectionRegistry, queryEngine, true);
        controller = new SearchController(searchIndexService, analyticsCaptureService, recordAuthorization);
    }

    private ResponseEntity<Map<String, Object>> search(String tenantId, String q, int limit) {
        return controller.search(tenantId, "user-1", q, limit, request);
    }

    private static Map<String, Object> row(String collection, String recordId) {
        return Map.of(
                "record_id", recordId,
                "collection_name", collection,
                "collection_id", "col-" + collection,
                "display_value", collection + " " + recordId,
                "rank", 0.5f);
    }

    private static List<Map<String, Object>> rows(String collection, String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> row(collection, prefix + i)).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static Object total(ResponseEntity<Map<String, Object>> response) {
        return ((Map<?, ?>) response.getBody().get("meta")).get("total");
    }

    @Nested
    @DisplayName("GET /api/_search")
    class Search {

        @Test
        @DisplayName("should return search results")
        void shouldReturnResults() {
            when(searchIndexService.search("tenant-1", "widget", SearchController.FETCH_SIZE, 0))
                    .thenReturn(List.of(Map.of(
                            "record_id", "rec-1",
                            "collection_name", "products",
                            "collection_id", "col-1",
                            "display_value", "Widget A",
                            "rank", 0.5f)));

            ResponseEntity<Map<String, Object>> response = search("tenant-1", "widget", 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> data = data(response);
            assertThat(data).hasSize(1);
            assertThat(data.get(0).get("id")).isEqualTo("rec-1");
            assertThat(data.get(0).get("type")).isEqualTo("products");
            assertThat(data.get(0).get("collectionName")).isEqualTo("products");
            assertThat(data.get(0).get("displayValue")).isEqualTo("Widget A");
            assertThat(total(response)).isEqualTo(1);
        }

        @Test
        @DisplayName("should return empty results for short queries")
        void shouldReturnEmptyForShortQuery() {
            ResponseEntity<Map<String, Object>> response = search("tenant-1", "ab", 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(data(response)).isEmpty();

            verifyNoInteractions(searchIndexService);
            // A too-short query is never captured.
            verifyNoInteractions(analyticsCaptureService);
        }

        @Test
        @DisplayName("should cap limit at 100")
        void shouldCapLimit() {
            when(searchIndexService.search("tenant-1", "test query", SearchController.FETCH_SIZE, 0))
                    .thenReturn(rows("products", "p", 150).subList(0, 100));
            when(searchIndexService.search("tenant-1", "test query", SearchController.FETCH_SIZE, 100))
                    .thenReturn(rows("products", "q", 50));

            ResponseEntity<Map<String, Object>> response = search("tenant-1", "test query", 500);

            assertThat(data(response)).hasSize(100);
            assertThat(total(response)).isEqualTo(100);
            // 100 readable hits from the first page: the index is not read further.
            verify(searchIndexService, times(1)).search(anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("should return 400 with populated JSON:API error for missing tenant ID")
        @SuppressWarnings("unchecked")
        void shouldReturn400ForMissingTenant() {
            ResponseEntity<Map<String, Object>> response = search("", "test", 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();

            List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
            assertThat(errors).hasSize(1);
            Map<String, Object> err = errors.get(0);
            assertThat(err.get("status")).isEqualTo("400");
            assertThat(err.get("code")).isEqualTo("MISSING_TENANT");
            assertThat(err.get("title")).isEqualTo("Bad Request");
            assertThat(err.get("detail")).isEqualTo("Missing X-Tenant-ID header");
        }
    }

    @Nested
    @DisplayName("per-hit authorization")
    class Authorization {

        @Test
        @DisplayName("a profile that reads only A gets only A's hits; meta.total counts only those")
        void onlyReadableCollection() {
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("invoices"), eq("read")))
                    .thenReturn(false);
            when(searchIndexService.search("tenant-1", "acme", SearchController.FETCH_SIZE, 0))
                    .thenReturn(List.of(row("invoices", "b1"), row("accounts", "a1"),
                            row("invoices", "b2"), row("accounts", "a2")));

            ResponseEntity<Map<String, Object>> response = search("tenant-1", "acme", 20);

            assertThat(data(response)).extracting(h -> h.get("id")).containsExactly("a1", "a2");
            assertThat(data(response)).extracting(h -> h.get("type")).containsOnly("accounts");
            assertThat(total(response)).isEqualTo(2);
            verify(authzService, never()).checkCollectionWideRecordAccess(any(), any(), any(), eq("_search"), any());
            verify(analyticsCaptureService).captureSearch("acme", false, null, "user-1");
        }

        @Test
        @DisplayName("a record-level rule drops one A hit; a manual READ share restores it")
        void recordRuleDenyAndShare() {
            when(recordRuleIndex.hasRecordVariantRules("tenant-1", "accounts")).thenReturn(true);
            when(searchIndexService.search("tenant-1", "acme", SearchController.FETCH_SIZE, 0))
                    .thenReturn(List.of(row("accounts", "a1"), row("accounts", "a2")));
            when(queryEngine.executeQuery(any(), any())).thenReturn(QueryResult.of(List.of(
                    Map.of("id", "a1", "tier", "restricted"),
                    Map.of("id", "a2", "tier", "open")), 2, new Pagination(1, 2)));
            when(authzService.batchCheckRecordAccess(any(), any(), any(), eq("accounts"), anyList(), eq("read")))
                    .thenReturn(Set.of("a2"));

            ResponseEntity<Map<String, Object>> denied = search("tenant-1", "acme", 20);
            assertThat(data(denied)).extracting(h -> h.get("id")).containsExactly("a2");
            assertThat(total(denied)).isEqualTo(1);

            request = new MockHttpServletRequest("GET", "/api/_search");
            when(permissionResolver.hasIdentity(request)).thenReturn(true);
            when(permissionResolver.getEmail(request)).thenReturn("user@example.com");
            when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
            when(permissionResolver.getTenantId(request)).thenReturn("tenant-1");
            when(recordShareAccessService.widen("user@example.com", "accounts", Set.of("a1"), "read"))
                    .thenReturn(Set.of("a1"));

            ResponseEntity<Map<String, Object>> shared = search("tenant-1", "acme", 20);
            assertThat(data(shared)).extracting(h -> h.get("id")).containsExactly("a1", "a2");
            assertThat(total(shared)).isEqualTo(2);
        }

        @Test
        @DisplayName("unreadable hits filling the first index page do not empty the response")
        void overFetchesPastUnreadableHits() {
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("invoices"), eq("read")))
                    .thenReturn(false);
            when(searchIndexService.search("tenant-1", "acme", SearchController.FETCH_SIZE, 0))
                    .thenReturn(rows("invoices", "b", SearchController.FETCH_SIZE));
            List<Map<String, Object>> second = new ArrayList<>(rows("invoices", "c", 10));
            second.addAll(rows("accounts", "a", 30));
            when(searchIndexService.search("tenant-1", "acme", SearchController.FETCH_SIZE, SearchController.FETCH_SIZE))
                    .thenReturn(second);

            ResponseEntity<Map<String, Object>> response = search("tenant-1", "acme", 20);

            assertThat(data(response)).hasSize(20);
            assertThat(data(response)).extracting(h -> h.get("type")).containsOnly("accounts");
            assertThat(data(response).get(0).get("id")).isEqualTo("a0");
            assertThat(total(response)).isEqualTo(20);
            // A short page means the index is exhausted.
            verify(searchIndexService, times(2)).search(anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("stops scanning the index after MAX_SCANNED rows")
        void capsTheScan() {
            when(authzService.checkCollectionWideRecordAccess(any(), any(), any(), eq("invoices"), eq("read")))
                    .thenReturn(false);
            when(searchIndexService.search(eq("tenant-1"), eq("acme"), anyInt(), anyInt()))
                    .thenAnswer(inv -> rows("invoices", "b" + inv.getArgument(3) + "-", inv.getArgument(2)));

            ResponseEntity<Map<String, Object>> response = search("tenant-1", "acme", 20);

            assertThat(data(response)).isEmpty();
            assertThat(total(response)).isEqualTo(0);
            verify(searchIndexService, times(SearchController.MAX_SCANNED / SearchController.FETCH_SIZE))
                    .search(anyString(), anyString(), anyInt(), anyInt());
            verify(searchIndexService, never()).search(anyString(), anyString(), anyInt(),
                    intThat(offset -> offset >= SearchController.MAX_SCANNED));
            verify(analyticsCaptureService).captureSearch("acme", true, null, "user-1");
        }
    }

    @Nested
    @DisplayName("analytics capture (consumer-alerting slice 8)")
    class Capture {

        @Test
        @DisplayName("captures the query with zeroResult=false when results exist")
        void capturesWithResults() {
            when(searchIndexService.search("tenant-1", "widget", SearchController.FETCH_SIZE, 0))
                    .thenReturn(List.of(row("products", "rec-1")));

            search("tenant-1", "widget", 20);

            verify(analyticsCaptureService).captureSearch("widget", false, null, "user-1");
        }

        @Test
        @DisplayName("captures zeroResult=true (the unmet-demand signal) on an empty result set")
        void capturesZeroResult() {
            when(searchIndexService.search("tenant-1", "nonexistent thing", SearchController.FETCH_SIZE, 0))
                    .thenReturn(List.of());

            search("tenant-1", "nonexistent thing", 20);

            verify(analyticsCaptureService).captureSearch("nonexistent thing", true, null, "user-1");
        }

        @Test
        @DisplayName("a capture failure never breaks the search response")
        void captureFailureDoesNotBreakSearch() {
            when(searchIndexService.search("tenant-1", "widget", SearchController.FETCH_SIZE, 0))
                    .thenReturn(List.of(row("products", "rec-1")));
            doThrow(new RuntimeException("capture boom"))
                    .when(analyticsCaptureService).captureSearch(anyString(), anyBoolean(), any(), any());

            ResponseEntity<Map<String, Object>> response = search("tenant-1", "widget", 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(data(response)).hasSize(1);
        }
    }
}
