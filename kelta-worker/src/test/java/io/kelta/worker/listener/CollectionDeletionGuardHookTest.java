package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.S3StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CollectionDeletionGuardHook Tests")
class CollectionDeletionGuardHookTest {

    private static final String COLLECTION_ID = "col-1";
    private static final String TENANT = "tenant-1";

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private S3StorageService storageService;
    @Mock private io.kelta.runtime.registry.CollectionRegistry collectionRegistry;
    @Mock private io.kelta.runtime.storage.StorageAdapter storageAdapter;
    @Mock private io.kelta.runtime.model.CollectionDefinition definition;

    private CollectionDeletionGuardHook hook;

    @BeforeEach
    void setUp() {
        hook = new CollectionDeletionGuardHook(
                jdbcTemplate, storageService, collectionRegistry, storageAdapter);
        // Default: every child table is empty.
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(COLLECTION_ID), eq(TENANT)))
                .thenReturn(0);
        when(storageService.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        // The pending-drop stash is a ThreadLocal; a leaked entry would bleed across tests.
        hook.afterDelete("collections", "drain", TENANT);
    }

    /** Makes the collection row resolve to a registered definition. */
    private void stubResolvableCollection() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("SELECT name FROM collection")),
                eq(String.class), eq(COLLECTION_ID), eq(TENANT)))
                .thenReturn("widgets");
        when(collectionRegistry.get("widgets")).thenReturn(definition);
    }

    /** Makes one child table report {@code count} rows. */
    private void stubChildCount(String table, int count) {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains(" " + table + " ")),
                eq(Integer.class), eq(COLLECTION_ID), eq(TENANT)))
                .thenReturn(count);
    }

    private void stubStorageKeys(String table, String... keys) {
        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains(" " + table + " ")),
                eq(String.class), eq(COLLECTION_ID), eq(TENANT)))
                .thenReturn(List.of(keys));
    }

    private void bindRequest(String forceValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (forceValue != null) {
            request.setParameter("force", forceValue);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private BeforeSaveResult delete() {
        return hook.beforeDelete("collections", COLLECTION_ID, TENANT);
    }

    @Nested
    @DisplayName("Force guard")
    class ForceGuard {

        @Test
        @DisplayName("a collection with no children deletes without force")
        void childlessCollectionPasses() {
            bindRequest(null);

            assertThat(delete().isSuccess()).isTrue();
            verify(storageService, never()).deleteObject(anyString());
        }

        @Test
        @DisplayName("children present and no force is rejected, naming what would be destroyed")
        void childrenWithoutForceIsBlocked() {
            stubChildCount("report", 2);
            stubChildCount("file_attachment", 5);
            bindRequest(null);

            BeforeSaveResult result = delete();

            assertThat(result.hasErrors()).isTrue();
            String message = result.getErrors().get(0).message();
            assertThat(message).contains("5 attachments").contains("2 reports");
            assertThat(message).contains("force=true");
        }

        @Test
        @DisplayName("nothing is destroyed when the guard rejects the delete")
        void blockedDeleteTouchesNoStorage() {
            stubChildCount("file_attachment", 3);
            stubStorageKeys("file_attachment", "tenant-1/a.pdf");
            bindRequest(null);

            assertThat(delete().hasErrors()).isTrue();
            verify(storageService, never()).deleteObject(anyString());
        }

        @Test
        @DisplayName("force=true admits the delete")
        void forceAdmitsDelete() {
            stubChildCount("report", 1);
            bindRequest("true");

            assertThat(delete().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("a non-collections delete is not guarded")
        void otherCollectionsPassThrough() {
            BeforeSaveResult result = hook.beforeDelete("accounts", "rec-1", TENANT);

            assertThat(result.isSuccess()).isTrue();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("no request context fails closed — an automated caller cannot force")
        void noRequestContextCannotForce() {
            stubChildCount("record_version", 12);
            RequestContextHolder.resetRequestAttributes();

            BeforeSaveResult result = delete();

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors().get(0).message()).contains("12 record versions");
            verify(storageService, never()).deleteObject(anyString());
        }

        @Test
        @DisplayName("FORCE bound to true forces a delete with no request (module uninstall with purgeData)")
        void scopedForceAdmitsDelete() {
            stubChildCount("report", 1);
            RequestContextHolder.resetRequestAttributes();

            BeforeSaveResult result = ScopedValue.where(CollectionDeletionGuardHook.FORCE, true).call(() -> delete());

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("FORCE bound to false wins over a request's force=true, so it cannot leak into the delete")
        void scopedFalseOverridesTheRequest() {
            stubChildCount("report", 1);
            bindRequest("true");

            BeforeSaveResult result = ScopedValue.where(CollectionDeletionGuardHook.FORCE, false).call(() -> delete());

            assertThat(result.hasErrors()).isTrue();
        }
    }

    @Nested
    @DisplayName("S3 cleanup")
    class StorageCleanup {

        @Test
        @DisplayName("attachment AND bulk-job objects are both deleted before the row cascade")
        void purgesBothStorageBearingTables() {
            stubChildCount("file_attachment", 2);
            stubChildCount("bulk_job", 1);
            stubStorageKeys("file_attachment", "tenant-1/a.pdf", "tenant-1/b.png");
            stubStorageKeys("bulk_job", "tenant-1/import.csv");
            bindRequest("true");

            assertThat(delete().isSuccess()).isTrue();

            verify(storageService).deleteObject("tenant-1/a.pdf");
            verify(storageService).deleteObject("tenant-1/b.png");
            verify(storageService).deleteObject("tenant-1/import.csv");
        }

        @Test
        @DisplayName("a failing object delete does not abort the confirmed collection delete")
        void storageFailureDoesNotBlockDelete() {
            stubChildCount("file_attachment", 1);
            stubStorageKeys("file_attachment", "tenant-1/gone.pdf");
            org.mockito.Mockito.doThrow(new RuntimeException("S3 down"))
                    .when(storageService).deleteObject("tenant-1/gone.pdf");
            bindRequest("true");

            assertThat(delete().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("storage disabled skips S3 entirely but still allows the delete")
        void storageDisabledSkipsPurge() {
            when(storageService.isEnabled()).thenReturn(false);
            stubChildCount("file_attachment", 1);
            bindRequest("true");

            assertThat(delete().isSuccess()).isTrue();
            verify(storageService, never()).deleteObject(anyString());
        }

        @Test
        @DisplayName("the physical table is dropped after the metadata delete commits")
        void dropsTableAfterDelete() {
            stubResolvableCollection();
            bindRequest(null);

            assertThat(delete().isSuccess()).isTrue();
            verify(storageAdapter, never()).dropCollection(any()); // not until afterDelete

            hook.afterDelete("collections", COLLECTION_ID, TENANT);

            verify(storageAdapter).dropCollection(definition);
        }

        @Test
        @DisplayName("a blocked delete drops nothing")
        void blockedDeleteDropsNoTable() {
            stubChildCount("report", 1);
            stubResolvableCollection();
            bindRequest(null);

            assertThat(delete().hasErrors()).isTrue();
            hook.afterDelete("collections", COLLECTION_ID, TENANT);

            verify(storageAdapter, never()).dropCollection(any());
        }

        @Test
        @DisplayName("an unresolvable collection leaks the table rather than dropping the wrong one")
        void unresolvedCollectionDropsNothing() {
            when(jdbcTemplate.queryForObject(
                    argThat(sql -> sql != null && sql.contains("SELECT name FROM collection")),
                    eq(String.class), eq(COLLECTION_ID), eq(TENANT)))
                    .thenReturn("widgets");
            when(collectionRegistry.get("widgets")).thenReturn(null);
            bindRequest(null);

            assertThat(delete().isSuccess()).isTrue();
            hook.afterDelete("collections", COLLECTION_ID, TENANT);

            verify(storageAdapter, never()).dropCollection(any());
        }

        @Test
        @DisplayName("a stale stash never drops a different collection's table")
        void staleStashDoesNotDropWrongTable() {
            stubResolvableCollection();
            bindRequest(null);
            assertThat(delete().isSuccess()).isTrue();

            // Same pooled thread, different collection reaching afterDelete.
            hook.afterDelete("collections", "some-other-collection", TENANT);

            verify(storageAdapter, never()).dropCollection(any());
        }

        @Test
        @DisplayName("a failing drop does not propagate — the delete already committed")
        void dropFailureIsSwallowed() {
            stubResolvableCollection();
            when(definition.name()).thenReturn("widgets");
            org.mockito.Mockito.doThrow(new RuntimeException("dependent object"))
                    .when(storageAdapter).dropCollection(definition);
            bindRequest(null);

            assertThat(delete().isSuccess()).isTrue();
            hook.afterDelete("collections", COLLECTION_ID, TENANT);  // must not throw
        }

        @Test
        @DisplayName("a child-count failure does not make the collection undeletable again")
        void countFailureDegradesToZero() {
            when(jdbcTemplate.queryForObject(
                    argThat(sql -> sql != null && sql.contains(" note ")),
                    eq(Integer.class), eq(COLLECTION_ID), eq(TENANT)))
                    .thenThrow(new RuntimeException("relation does not exist"));
            bindRequest(null);

            assertThat(delete().isSuccess()).isTrue();
        }
    }
}
