package io.kelta.worker.service;

import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CheckResourcesRequestBuilder;
import dev.cerbos.sdk.CheckResourcesResult;
import dev.cerbos.sdk.CheckResult;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.config.WorkerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CerbosAuthorizationService (Worker)")
class CerbosAuthorizationServiceTest {

    @Mock
    private CerbosBlockingClient cerbosClient;

    @Mock(answer = org.mockito.Answers.RETURNS_SELF)
    private CheckResourcesRequestBuilder batchRequest;

    @Mock
    private CheckResourcesResult batchResult;

    @Mock
    private CheckResult fieldCheckResult;

    private CerbosAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new CerbosAuthorizationService(cerbosClient, new SimpleMeterRegistry(), new WorkerProperties());
    }

    @Nested
    @DisplayName("Field access caching")
    class FieldAccessCaching {

        @Test
        @DisplayName("Should cache field access results and serve from cache on repeat calls")
        void cachesFieldAccess() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(batchResult.find("field-2")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> fields = List.of("field-1", "field-2");

            // First call — cache miss
            List<String> result1 = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            assertThat(result1).containsExactly("field-1", "field-2");

            // Second call — cache hit
            List<String> result2 = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            assertThat(result2).containsExactly("field-1", "field-2");

            // Cerbos should only be called once
            verify(cerbosClient, times(1)).batch(any());
        }

        @Test
        @DisplayName("Should serve subset of cached allowed fields when checking fewer fields")
        void servesSubsetFromCache() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(batchResult.find("field-2")).thenReturn(Optional.of(fieldCheckResult));
            when(batchResult.find("field-3")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true, true, false);

            // First call with all fields
            List<String> result1 = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1",
                    List.of("field-1", "field-2", "field-3"), "read");
            assertThat(result1).containsExactly("field-1", "field-2");

            // Second call with subset — should filter from cache
            List<String> result2 = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1",
                    List.of("field-1", "field-3"), "read");
            assertThat(result2).containsExactly("field-1");

            verify(cerbosClient, times(1)).batch(any());
        }

        @Test
        @DisplayName("Should use separate cache entries for different collections")
        void separatesByCollection() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> fields = List.of("field-1");

            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-2", fields, "read");

            // Two different collections = two Cerbos calls
            verify(cerbosClient, times(2)).batch(any());
        }
    }

    @Nested
    @DisplayName("Per-action cache isolation")
    class PerActionCacheIsolation {

        @Test
        @DisplayName("Should not serve a cached read result for a write check")
        void readCacheDoesNotServeWrite() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);
            when(fieldCheckResult.isAllowed("write")).thenReturn(false);

            List<String> fields = List.of("field-1");

            List<String> readResult = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            List<String> writeResult = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "write");

            // READ_ONLY semantics: read allowed, write denied — a shared cache
            // entry would have leaked the read allow-list into the write check.
            assertThat(readResult).containsExactly("field-1");
            assertThat(writeResult).isEmpty();
            verify(cerbosClient, times(2)).batch(any());
        }

        @Test
        @DisplayName("Should not serve a cached read result for an unmask check")
        void readCacheDoesNotServeUnmask() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);
            when(fieldCheckResult.isAllowed("unmask")).thenReturn(false);

            List<String> fields = List.of("field-1");

            List<String> readResult = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            List<String> unmaskResult = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "unmask");

            // MASKED semantics: read allowed (value renders redacted), unmask denied.
            assertThat(readResult).containsExactly("field-1");
            assertThat(unmaskResult).isEmpty();
            verify(cerbosClient, times(2)).batch(any());
        }

        @Test
        @DisplayName("Should still cache repeat checks per action")
        void cachesPerAction() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);
            when(fieldCheckResult.isAllowed("unmask")).thenReturn(false);

            List<String> fields = List.of("field-1");

            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "unmask");
            // Repeats — both must be cache hits.
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "unmask");

            verify(cerbosClient, times(2)).batch(any());
        }
    }

    @Nested
    @DisplayName("Cache eviction")
    class CacheEviction {

        @Test
        @DisplayName("Should evict cached field access for tenant and re-check")
        void evictsForTenant() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> fields = List.of("field-1");

            // Populate cache
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");

            // Evict
            service.evictForTenant("tenant-1");

            // Should call Cerbos again
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");

            verify(cerbosClient, times(2)).batch(any());
        }

        @Test
        @DisplayName("Should not evict entries for other tenants")
        void doesNotEvictOtherTenants() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> fields = List.of("field-1");

            // Populate cache for tenant-1
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");

            // Evict for different tenant
            service.evictForTenant("tenant-other");

            // Should still serve from cache
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");

            verify(cerbosClient, times(1)).batch(any());
        }

        @Test
        @DisplayName("Should evict all actions' entries for a collection and re-check")
        void evictsForCollectionAcrossActions() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);
            when(fieldCheckResult.isAllowed("unmask")).thenReturn(true);

            List<String> fields = List.of("field-1");

            // Populate cache entries for two actions of the same collection
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "unmask");
            verify(cerbosClient, times(2)).batch(any());

            service.evictForCollection("tenant-1", "col-1");

            // Both actions must re-query Cerbos
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "unmask");
            verify(cerbosClient, times(4)).batch(any());
        }

        @Test
        @DisplayName("Should not evict entries for other collections of the tenant")
        void evictForCollectionLeavesOtherCollectionsAlone() throws Exception {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find("field-1")).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> fields = List.of("field-1");

            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-2", fields, "read");
            verify(cerbosClient, times(2)).batch(any());

            service.evictForCollection("tenant-1", "col-1");

            // col-2 entry must survive the eviction — cache hit, no new Cerbos call.
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-2", fields, "read");
            verify(cerbosClient, times(2)).batch(any());

            // col-1 entry is gone — re-query.
            service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            verify(cerbosClient, times(3)).batch(any());
        }
    }

    @Nested
    @DisplayName("Empty and edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return empty list for empty field list")
        void emptyFieldList() {
            List<String> result = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", List.of(), "read");
            assertThat(result).isEmpty();
            verifyNoInteractions(cerbosClient);
        }
    }

    @Nested
    @DisplayName("Batch chunking (Cerbos 50-resource request limit)")
    class BatchChunking {

        private List<java.util.Map<String, Object>> records(int count) {
            List<java.util.Map<String, Object>> records = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                records.add(java.util.Map.of("id", "rec-" + i, "attributes", java.util.Map.of()));
            }
            return records;
        }

        @Test
        @DisplayName("record check over 50 records splits into multiple Cerbos calls and allows all")
        void recordCheckChunksLargePages() {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find(anyString())).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            java.util.Set<String> allowed = service.batchCheckRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", records(82), "read");

            // 82 records → 50 + 32; a single 82-resource call is rejected by the
            // Cerbos server (INVALID_ARGUMENT over its 50-resource request limit).
            assertThat(allowed).hasSize(82);
            verify(cerbosClient, times(2)).batch(any());
        }

        @Test
        @DisplayName("a failed record chunk is denied without blanking the other chunks")
        void failedRecordChunkDegradesPartially() {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check())
                    .thenReturn(batchResult)
                    .thenThrow(new RuntimeException("cerbos unavailable"));
            when(batchResult.find(anyString())).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            java.util.Set<String> allowed = service.batchCheckRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", records(82), "read");

            // First chunk (records 0-49) allowed; failed second chunk fail-closed.
            assertThat(allowed).hasSize(50);
            assertThat(allowed).contains("rec-0", "rec-49").doesNotContain("rec-50", "rec-81");
        }

        @Test
        @DisplayName("field check over 50 fields splits into multiple Cerbos calls and caches the union")
        void fieldCheckChunksLargeFieldSets() {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenReturn(batchResult);
            when(batchResult.find(anyString())).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> fields = new java.util.ArrayList<>();
            for (int i = 0; i < 120; i++) {
                fields.add("field-" + i);
            }

            List<String> result = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            assertThat(result).hasSize(120);
            verify(cerbosClient, times(3)).batch(any());

            // Second call is fully served from the cache — no further Cerbos calls.
            List<String> cached = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            assertThat(cached).hasSize(120);
            verify(cerbosClient, times(3)).batch(any());
        }

        @Test
        @DisplayName("a failed field chunk denies everything and skips the cache write")
        void failedFieldChunkDeniesAllAndDoesNotCache() {
            when(cerbosClient.batch(any(Principal.class))).thenReturn(batchRequest);
            when(batchRequest.check()).thenThrow(new RuntimeException("cerbos unavailable"));

            List<String> fields = new java.util.ArrayList<>();
            for (int i = 0; i < 60; i++) {
                fields.add("field-" + i);
            }

            List<String> result = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            assertThat(result).isEmpty();

            // No cache entry was written: the retry goes back to Cerbos.
            // doReturn: re-stubbing check() via when() would invoke the throwing stub.
            doReturn(batchResult).when(batchRequest).check();
            when(batchResult.find(anyString())).thenReturn(Optional.of(fieldCheckResult));
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            List<String> retry = service.batchCheckFieldAccess(
                    "user@test.com", "profile-1", "tenant-1", "col-1", fields, "read");
            assertThat(retry).hasSize(60);
        }
    }

    @Nested
    @DisplayName("Principal attributes")
    class PrincipalAttributes {

        private static final String ALICE = "11111111-1111-1111-1111-111111111111";

        private dev.cerbos.api.v1.engine.Engine.Principal capturedPrincipal() {
            ArgumentCaptor<Principal> captor = ArgumentCaptor.forClass(Principal.class);
            verify(cerbosClient).check(captor.capture(), any(Resource.class), eq("read"));
            return captor.getValue().toPrincipal();
        }

        @Test
        @DisplayName("carries the bound caller's UUID as attr.userId and keeps P.id as the email")
        void carriesCallerUuid() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);
            CallerContext alice = new CallerContext(ALICE, CallerContext.UserType.INTERNAL, false, false);

            CallerContext.runAs(alice, () -> service.checkRecordAccess("alice@test.com", "profile-1",
                    "tenant-1", "contacts", "rec-1", java.util.Map.of(), "read"));

            var principal = capturedPrincipal();
            assertThat(principal.getId()).isEqualTo("alice@test.com");
            assertThat(principal.getAttrMap().get("userId").getStringValue()).isEqualTo(ALICE);
        }

        @Test
        @DisplayName("renders an empty attr.userId on the internal tier (no caller bound)")
        void emptyUserIdWithoutCaller() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            service.checkRecordAccess("alice@test.com", "profile-1",
                    "tenant-1", "contacts", "rec-1", java.util.Map.of(), "read");

            var principal = capturedPrincipal();
            assertThat(principal.getAttrMap()).containsKey("userId");
            assertThat(principal.getAttrMap().get("userId").getStringValue()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Collection-wide record access (record-invariant short-circuit)")
    class CollectionWideRecordAccess {

        @Test
        @DisplayName("ALLOW is cached per (tenant, profile, collection, action)")
        void allowIsCached() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            assertThat(service.checkCollectionWideRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "contacts", "read")).isTrue();
            assertThat(service.checkCollectionWideRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "contacts", "read")).isTrue();

            verify(cerbosClient, times(1)).check(any(), any(), eq("read"));
        }

        @Test
        @DisplayName("DENY is returned but never cached (could be a fail-closed artifact)")
        void denyIsNotCached() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("read")).thenReturn(false);

            assertThat(service.checkCollectionWideRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "contacts", "read")).isFalse();
            assertThat(service.checkCollectionWideRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "contacts", "read")).isFalse();

            verify(cerbosClient, times(2)).check(any(), any(), eq("read"));
        }

        @Test
        @DisplayName("evictForTenant clears the cached collection-wide decisions")
        void evictionClearsCache() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("read")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("read")).thenReturn(true);

            service.checkCollectionWideRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "contacts", "read");
            service.evictForTenant("tenant-1");
            service.checkCollectionWideRecordAccess(
                    "user@test.com", "profile-1", "tenant-1", "contacts", "read");

            verify(cerbosClient, times(2)).check(any(), any(), eq("read"));
        }
    }

    @Nested
    @DisplayName("Collection (object-level) access")
    class CollectionAccess {

        @Test
        @DisplayName("allows when Cerbos allows the collection action")
        void allowsWhenCerbosAllows() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("create")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("create")).thenReturn(true);

            boolean allowed = service.checkCollectionAccess(
                    "user@test.com", "profile-1", "tenant-1", "uuid-c", "create");

            assertThat(allowed).isTrue();
        }

        @Test
        @DisplayName("denies when Cerbos denies the collection action")
        void deniesWhenCerbosDenies() {
            when(cerbosClient.check(any(Principal.class), any(Resource.class), eq("delete")))
                    .thenReturn(fieldCheckResult);
            when(fieldCheckResult.isAllowed("delete")).thenReturn(false);

            boolean allowed = service.checkCollectionAccess(
                    "user@test.com", "profile-1", "tenant-1", "uuid-c", "delete");

            assertThat(allowed).isFalse();
        }
    }
}
