package io.kelta.worker.listener;

import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.ApprovalRepository;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ApprovalRecordLockHook")
@WithTenant("t1")
class ApprovalRecordLockHookTest {

    private ApprovalRepository approvalRepository;
    private CollectionRegistry collectionRegistry;
    private ApprovalRecordLockHook hook;

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        collectionRegistry = mock(CollectionRegistry.class);
        hook = new ApprovalRecordLockHook(approvalRepository, collectionRegistry);
    }

    @Test
    @DisplayName("Should use wildcard collection name")
    void shouldUseWildcard() {
        assertThat(hook.getCollectionName()).isEqualTo("*");
    }

    @Test
    @DisplayName("Should have order 50 (before audit hooks)")
    void shouldHaveOrder50() {
        assertThat(hook.getOrder()).isEqualTo(50);
    }

    @Test
    @DisplayName("Should block update when record is locked")
    void shouldBlockUpdateWhenLocked() {
        var record = new HashMap<String, Object>();
        record.put("__collectionId", "col-1");
        record.put("__collectionName", "accounts");

        when(approvalRepository.getRecordEditability("col-1", "rec-1", "t1"))
                .thenReturn(Optional.of("LOCKED"));

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, Map.of(), "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().getFirst().message()).contains("locked by an active approval");
    }

    @Test
    @DisplayName("Should allow update when no active approval")
    void shouldAllowUpdateWhenNoApproval() {
        var record = new HashMap<String, Object>();
        record.put("__collectionId", "col-1");
        record.put("__collectionName", "accounts");

        when(approvalRepository.getRecordEditability("col-1", "rec-1", "t1"))
                .thenReturn(Optional.empty());

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, Map.of(), "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should allow update when record editability is UNLOCKED")
    void shouldAllowUpdateWhenUnlocked() {
        var record = new HashMap<String, Object>();
        record.put("__collectionId", "col-1");
        record.put("__collectionName", "accounts");

        when(approvalRepository.getRecordEditability("col-1", "rec-1", "t1"))
                .thenReturn(Optional.of("UNLOCKED"));

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, Map.of(), "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should skip system collections")
    void shouldSkipSystemCollections() {
        var record = new HashMap<String, Object>();
        record.put("__collectionId", "col-1");
        record.put("__collectionName", "approval-processes");

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, Map.of(), "t1");

        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(approvalRepository);
    }

    @Test
    @DisplayName("Should skip when no collectionId in record")
    void shouldSkipWhenNoCollectionId() {
        var record = new HashMap<String, Object>();
        record.put("name", "test");

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, Map.of(), "t1");

        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(approvalRepository);
    }
}
