package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.registry.CollectionRegistry;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for system collection caching behavior in DynamicCollectionRouter.
 */
@DisplayName("DynamicCollectionRouter Cache Tests")
class DynamicCollectionRouterCacheTest {

    private CollectionRegistry registry;
    private QueryEngine queryEngine;
    private SystemCollectionCache cache;
    private DynamicCollectionRouter router;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        cache = mock(SystemCollectionCache.class);
        router = new DynamicCollectionRouter(registry, queryEngine);
        router.setSystemCollectionCache(cache);
        mockMvc = MockMvcBuilders.standaloneSetup(router).build();
        objectMapper = new ObjectMapper();
    }

    private CollectionDefinition buildSystemCollection(String name) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .displayName(name)
                .addField(FieldDefinition.requiredString("name"))
                .systemCollection(true)
                .tenantScoped(true)
                .build();
    }

    private CollectionDefinition buildNonSystemCollection(String name) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .displayName(name)
                .addField(FieldDefinition.requiredString("name"))
                .build();
    }

    private CollectionDefinition buildReadOnlySystemCollection(String name) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .displayName(name)
                .addField(FieldDefinition.requiredString("name"))
                .systemCollection(true)
                .tenantScoped(true)
                .readOnly(true)
                .build();
    }

    private CollectionDefinition buildOwnerScopedSystemCollection(String name, boolean scopedReads) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .displayName(name)
                .addField(FieldDefinition.requiredString("userId"))
                .systemCollection(true)
                .tenantScoped(true)
                .ownerField("userId")
                .ownerScope(OwnerScope.ALL)
                .ownerScopeReads(scopedReads)
                .build();
    }

    private String jsonApiBody(Map<String, Object> attributes) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "test");
        data.put("attributes", attributes);
        Map<String, Object> body = new HashMap<>();
        body.put("data", data);
        return objectMapper.writeValueAsString(body);
    }

    @Nested
    @DisplayName("List - Cache Behavior")
    class ListCacheTests {

        @Test
        @DisplayName("Should return cached response for system collection list on cache hit")
        void list_returnsCachedResponse_onCacheHit() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            Map<String, Object> cachedResponse = Map.of(
                    "data", List.of(Map.of("type", "collections", "id", "1")),
                    "metadata", Map.of("totalCount", 1, "currentPage", 1, "pageSize", 20, "totalPages", 1)
            );
            when(cache.getListResponse(eq("tenant-1"), eq("collections"), anyString()))
                    .thenReturn(Optional.of(cachedResponse));

            mockMvc.perform(get("/api/collections")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());

            // Query engine should NOT have been called
            verify(queryEngine, never()).executeQuery(any(), any());
        }

        @Test
        @DisplayName("Should query DB and cache response on cache miss for system collection")
        void list_queriesDbAndCaches_onCacheMiss() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            when(cache.getListResponse(eq("tenant-1"), eq("collections"), anyString()))
                    .thenReturn(Optional.empty());

            QueryResult result = new QueryResult(
                    List.of(Map.of("id", "1", "name", "Products")),
                    new PaginationMetadata(1, 1, 20, 1)
            );
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/collections")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            // Verify the response was cached
            verify(cache).putListResponse(eq("tenant-1"), eq("collections"), anyString(), any());
        }

        @Test
        @DisplayName("Should NOT cache response for non-system collections")
        void list_doesNotCache_forNonSystemCollection() throws Exception {
            CollectionDefinition def = buildNonSystemCollection("products");
            when(registry.get("products")).thenReturn(def);

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/products")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            // Cache should not have been consulted
            verify(cache, never()).getListResponse(any(), any(), any());
            verify(cache, never()).putListResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should NOT cache response for read-only system collections")
        void list_doesNotCache_forReadOnlySystemCollection() throws Exception {
            // record-versions / field-history / email-logs etc. are written by backend
            // services via direct JDBC — no write path ever evicts their cache entries,
            // so caching them serves stale per-pod results until the TTL.
            CollectionDefinition def = buildReadOnlySystemCollection("record-versions");
            when(registry.get("record-versions")).thenReturn(def);

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/record-versions")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache, never()).getListResponse(any(), any(), any());
            verify(cache, never()).putListResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should NOT cache scheduled-jobs — it is mutated out-of-band by the scheduler")
        void list_doesNotCache_forScheduledJobs() throws Exception {
            // scheduled-jobs is writable through this router, so readOnly does not exclude it, but
            // every meaningful write happens in ScheduledJobRepository via direct JDBC: the
            // scheduler stamps last_run_at/last_status/next_run_at after every execution, and
            // FlowScheduleSyncHook rewrites active/next_run_at when a flow's schedule changes.
            // Nothing evicts the entry, and the cache is per-pod so eviction-on-write would not
            // help either. Caching it made the endpoint report active=false/next_run_at=null for a
            // job that was active with a correct next run — indistinguishable from a dead scheduler.
            CollectionDefinition def = buildSystemCollection("scheduled-jobs");
            when(registry.get("scheduled-jobs")).thenReturn(def);

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/scheduled-jobs")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache, never()).getListResponse(any(), any(), any());
            verify(cache, never()).putListResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should still cache other writable system collections")
        void list_stillCaches_forOtherWritableSystemCollections() throws Exception {
            // Guards the exclusion from being widened by accident.
            CollectionDefinition def = buildSystemCollection("ui-menus");
            when(registry.get("ui-menus")).thenReturn(def);
            when(cache.getListResponse(any(), any(), any())).thenReturn(Optional.empty());

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/ui-menus")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache).putListResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should NOT cache owner-scoped reads — the cache key carries no caller")
        void list_doesNotCache_forOwnerScopedReads() throws Exception {
            // The storage adapter narrows the read to the bound caller's rows. A cached listing
            // keyed only on tenant + query would be served to the next member with the same query.
            CollectionDefinition def = buildOwnerScopedSystemCollection("user-ui-preferences", true);
            when(registry.get("user-ui-preferences")).thenReturn(def);

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/user-ui-preferences")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache, never()).getListResponse(any(), any(), any());
            verify(cache, never()).putListResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should still cache owner-scoped collections whose reads are unscoped")
        void list_stillCaches_forOwnerScopedWritesOnly() throws Exception {
            CollectionDefinition def = buildOwnerScopedSystemCollection("notes", false);
            when(registry.get("notes")).thenReturn(def);
            when(cache.getListResponse(any(), any(), any())).thenReturn(Optional.empty());

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/notes")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache).putListResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should NOT cache response when include parameter is present")
        void list_doesNotCache_whenIncludePresent() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            mockMvc.perform(get("/api/collections")
                            .param("include", "fields")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            // Cache should not have been used when includes are requested
            verify(cache, never()).getListResponse(any(), any(), any());
            verify(cache, never()).putListResponse(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Get By ID - Cache Behavior")
    class GetByIdCacheTests {

        @Test
        @DisplayName("Should return cached response for system collection get on cache hit")
        void get_returnsCachedResponse_onCacheHit() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            Map<String, Object> cachedResponse = Map.of(
                    "data", Map.of("type", "collections", "id", "abc", "attributes", Map.of("name", "Products"))
            );
            when(cache.getByIdResponse("tenant-1", "collections", "abc"))
                    .thenReturn(Optional.of(cachedResponse));

            mockMvc.perform(get("/api/collections/abc")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("abc"));

            // Query engine should NOT have been called
            verify(queryEngine, never()).getById(any(), any());
        }

        @Test
        @DisplayName("Should query DB and cache response on cache miss")
        void get_queriesDbAndCaches_onCacheMiss() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            when(cache.getByIdResponse("tenant-1", "collections", "abc"))
                    .thenReturn(Optional.empty());

            Map<String, Object> record = Map.of("id", "abc", "name", "Products");
            when(queryEngine.getById(def, "abc")).thenReturn(Optional.of(record));

            mockMvc.perform(get("/api/collections/abc")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            // Verify the response was cached
            verify(cache).putByIdResponse(eq("tenant-1"), eq("collections"), eq("abc"), any());
        }

        @Test
        @DisplayName("Should NOT consult or fill cache for read-only system collections")
        void get_doesNotUseCache_forReadOnlySystemCollection() throws Exception {
            CollectionDefinition def = buildReadOnlySystemCollection("record-versions");
            when(registry.get("record-versions")).thenReturn(def);

            Map<String, Object> record = Map.of("id", "abc", "name", "v1");
            when(queryEngine.getById(def, "abc")).thenReturn(Optional.of(record));

            mockMvc.perform(get("/api/record-versions/abc")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache, never()).getByIdResponse(any(), any(), any());
            verify(cache, never()).putByIdResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should NOT consult or fill cache for owner-scoped reads")
        void get_doesNotUseCache_forOwnerScopedReads() throws Exception {
            CollectionDefinition def = buildOwnerScopedSystemCollection("user-ui-preferences", true);
            when(registry.get("user-ui-preferences")).thenReturn(def);

            Map<String, Object> record = Map.of("id", "abc", "userId", "u-1");
            when(queryEngine.getById(def, "abc")).thenReturn(Optional.of(record));

            mockMvc.perform(get("/api/user-ui-preferences/abc")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache, never()).getByIdResponse(any(), any(), any());
            verify(cache, never()).putByIdResponse(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should NOT consult cache when include parameter is present")
        void get_skipsCacheLookup_whenIncludePresent() throws Exception {
            // Regression: a cached no-include response must not shadow include-bearing
            // requests, otherwise newly created child rows are invisible until TTL.
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            // Cache has a stale no-include response — should be ignored
            Map<String, Object> stale = Map.of(
                    "data", Map.of("type", "collections", "id", "abc", "attributes", Map.of("name", "Products"))
            );
            when(cache.getByIdResponse("tenant-1", "collections", "abc"))
                    .thenReturn(Optional.of(stale));

            Map<String, Object> record = Map.of("id", "abc", "name", "Products");
            when(queryEngine.getById(def, "abc")).thenReturn(Optional.of(record));

            mockMvc.perform(get("/api/collections/abc")
                            .param("include", "fields")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            // Cache lookup must be skipped — fresh DB read instead
            verify(cache, never()).getByIdResponse(any(), any(), any());
            verify(queryEngine).getById(def, "abc");
        }

        @Test
        @DisplayName("Should NOT cache response when include parameter is present")
        void get_doesNotCache_whenIncludePresent() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            Map<String, Object> record = Map.of("id", "abc", "name", "Products");
            when(queryEngine.getById(def, "abc")).thenReturn(Optional.of(record));

            mockMvc.perform(get("/api/collections/abc")
                            .param("include", "fields")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache, never()).putByIdResponse(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Write Operations - Cache Eviction")
    class WriteEvictionTests {

        @Test
        @DisplayName("Should evict cache on create")
        void create_evictsCache() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            Map<String, Object> created = Map.of("id", "new-1", "name", "Orders");
            when(queryEngine.create(eq(def), any())).thenReturn(created);

            mockMvc.perform(post("/api/collections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonApiBody(Map.of("name", "Orders")))
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isCreated());

            verify(cache).evict("tenant-1", "collections");
        }

        @Test
        @DisplayName("Should evict cache on update")
        void update_evictsCache() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            Map<String, Object> updated = Map.of("id", "abc", "name", "Updated");
            when(queryEngine.update(eq(def), eq("abc"), any())).thenReturn(Optional.of(updated));

            mockMvc.perform(put("/api/collections/abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonApiBody(Map.of("name", "Updated")))
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(cache).evict("tenant-1", "collections");
        }

        @Test
        @DisplayName("Should evict cache on delete")
        void delete_evictsCache() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            when(queryEngine.delete(def, "abc")).thenReturn(true);

            mockMvc.perform(delete("/api/collections/abc")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isNoContent());

            verify(cache).evict("tenant-1", "collections");
        }

        @Test
        @DisplayName("Should NOT evict cache when delete returns not found")
        void delete_doesNotEvictCache_whenNotFound() throws Exception {
            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            when(queryEngine.delete(def, "abc")).thenReturn(false);

            mockMvc.perform(delete("/api/collections/abc")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isNotFound());

            verify(cache, never()).evict(any(), any());
        }

        @Test
        @DisplayName("Should NOT evict cache for non-system collection writes")
        void create_doesNotEvictCache_forNonSystemCollection() throws Exception {
            CollectionDefinition def = buildNonSystemCollection("products");
            when(registry.get("products")).thenReturn(def);

            Map<String, Object> created = Map.of("id", "new-1", "name", "Widget");
            when(queryEngine.create(eq(def), any())).thenReturn(created);

            mockMvc.perform(post("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonApiBody(Map.of("name", "Widget")))
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isCreated());

            verify(cache, never()).evict(any(), any());
        }
    }

    @Nested
    @DisplayName("No Cache Configured")
    class NoCacheTests {

        @Test
        @DisplayName("Should work normally when no cache is configured")
        void list_worksWithoutCache() throws Exception {
            DynamicCollectionRouter routerNoCache = new DynamicCollectionRouter(registry, queryEngine);
            // Don't set cache
            MockMvc noMvc = MockMvcBuilders.standaloneSetup(routerNoCache).build();

            CollectionDefinition def = buildSystemCollection("collections");
            when(registry.get("collections")).thenReturn(def);

            QueryResult result = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(result);

            noMvc.perform(get("/api/collections")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isOk());

            verify(queryEngine).executeQuery(eq(def), any());
        }
    }
}
