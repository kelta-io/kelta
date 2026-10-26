package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.registry.ConcurrentCollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.ValidationRuleDefinition;
import io.kelta.runtime.validation.ValidationRuleRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.service.SearchIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ValidationRuleRefreshHook")
class ValidationRuleRefreshHookTest {

    private static final String TENANT = "tenant-1";
    private static final String COLLECTION_ID = "col-1";
    private static final String SUBJECT = CollectionConfigEventPublisher.SUBJECT_PREFIX + COLLECTION_ID;

    private PlatformEventPublisher publisher;
    private JdbcTemplate jdbc;
    private ValidationRuleRefreshHook hook;

    @BeforeEach
    void setUp() {
        publisher = mock(PlatformEventPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM collection WHERE id"), eq(COLLECTION_ID)))
                .thenReturn(List.of(Map.of("name", "orders", "tenant_id", TENANT)));
        hook = new ValidationRuleRefreshHook(publisher, jdbc);
    }

    @Test
    @DisplayName("a created rule refreshes its collection on every pod")
    void createPublishes() {
        hook.afterCreate(Map.of("id", "rule-1", "collectionId", COLLECTION_ID), TENANT);

        assertCollectionChangedPublished();
    }

    @Test
    @DisplayName("an updated rule refreshes its collection on every pod")
    void updatePublishes() {
        Map<String, Object> rule = Map.of("id", "rule-1", "collectionId", COLLECTION_ID);
        hook.afterUpdate("rule-1", rule, rule, TENANT);

        assertCollectionChangedPublished();
    }

    @Test
    @DisplayName("a deleted rule refreshes the collection captured before the row was gone")
    void deletePublishesTheCollectionCapturedBeforeDelete() {
        when(jdbc.queryForList(ValidationRuleRefreshHook.SELECT_RULE_COLLECTION_ID, "rule-1"))
                .thenReturn(List.of(Map.of("collection_id", COLLECTION_ID)))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeDelete("rule-1", TENANT);
        hook.afterDelete("rule-1", TENANT);

        assertThat(result.isSuccess()).isTrue();
        assertCollectionChangedPublished();
    }

    @Test
    @DisplayName("a delete whose rule lookup failed is logged and skipped, never blocked")
    void deleteWithFailedLookupSkipsTheBroadcast() {
        when(jdbc.queryForList(ValidationRuleRefreshHook.SELECT_RULE_COLLECTION_ID, "rule-1"))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThat(hook.beforeDelete("rule-1", TENANT).isSuccess()).isTrue();
        hook.afterDelete("rule-1", TENANT);

        verify(publisher, never()).publish(anyString(), any());
    }

    /**
     * Pod 2 enforces a rule; pod 1 deletes it. The event pod 1's hook publishes, consumed by pod
     * 2's {@link CollectionSchemaListener}, is what makes pod 2 stop enforcing it.
     */
    @Test
    @DisplayName("a deleted rule stops being enforced on another pod once it consumes the delete event")
    void deletedRuleStopsBeingEnforcedOnAnotherPod() {
        Map<String, Object> collectionRow = new HashMap<>(Map.of(
                "id", COLLECTION_ID, "name", "orders", "active", true,
                "system_collection", false, "tenant_id", TENANT));
        Map<String, Object> ruleRow = new HashMap<>(Map.of(
                "tenant_id", TENANT, "name", "cap", "error_condition_formula", "total > 100",
                "error_message", "Orders are capped at 100", "evaluate_on", "CREATE_AND_UPDATE",
                "active", true, "severity", "ERROR"));
        JdbcTemplate pod2Jdbc = mock(JdbcTemplate.class);
        when(pod2Jdbc.queryForList(contains("FROM collection WHERE id"), eq(COLLECTION_ID)))
                .thenReturn(List.of(collectionRow));
        when(pod2Jdbc.queryForList(contains("FROM validation_rule"), eq(COLLECTION_ID), eq(TENANT)))
                .thenReturn(List.of(ruleRow))
                .thenReturn(List.of());

        ValidationRuleRegistry pod2Rules = new ValidationRuleRegistry();
        CollectionLifecycleManager pod2 = new CollectionLifecycleManager(new ConcurrentCollectionRegistry(),
                mock(StorageAdapter.class), pod2Jdbc, new ObjectMapper());
        pod2.setValidationRuleRegistry(pod2Rules);
        pod2.initializeCollection(COLLECTION_ID, false);
        assertThat(pod2Rules.getActiveRules(TENANT, "orders"))
                .extracting(ValidationRuleDefinition::name).containsExactly("cap");

        when(jdbc.queryForList(ValidationRuleRefreshHook.SELECT_RULE_COLLECTION_ID, "rule-1"))
                .thenReturn(List.of(Map.of("collection_id", COLLECTION_ID)));
        hook.beforeDelete("rule-1", TENANT);
        hook.afterDelete("rule-1", TENANT);

        ObjectMapper mapper = new ObjectMapper();
        CollectionSchemaListener pod2Listener = new CollectionSchemaListener(pod2,
                mock(SearchIndexService.class), mock(CerbosAuthorizationService.class), mapper);
        pod2Listener.handleCollectionChanged(mapper.writeValueAsString(publishedEvent()));

        assertThat(pod2Rules.getActiveRules(TENANT, "orders")).isEmpty();
    }

    private void assertCollectionChangedPublished() {
        PlatformEvent<CollectionChangedPayload> event = publishedEvent();
        assertThat(event.getTenantId()).isEqualTo(TENANT);
        assertThat(event.getPayload().getId()).isEqualTo(COLLECTION_ID);
        assertThat(event.getPayload().getName()).isEqualTo("orders");
        assertThat(event.getPayload().getChangeType()).isEqualTo(ChangeType.UPDATED);
    }

    @SuppressWarnings("unchecked")
    private PlatformEvent<CollectionChangedPayload> publishedEvent() {
        ArgumentCaptor<PlatformEvent<CollectionChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher).publish(eq(SUBJECT), captor.capture());
        return captor.getValue();
    }
}
