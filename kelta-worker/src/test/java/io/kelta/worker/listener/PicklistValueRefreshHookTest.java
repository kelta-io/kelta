package io.kelta.worker.listener;

import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PicklistValueRefreshHook (PLT-711)")
class PicklistValueRefreshHookTest {

    private static final String TENANT = "tenant-1";

    private PlatformEventPublisher publisher;
    private JdbcTemplate jdbc;
    private CollectionLifecycleManager lifecycleManager;
    private PicklistValueRefreshHook hook;

    @BeforeEach
    void setUp() {
        publisher = mock(PlatformEventPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        hook = new PicklistValueRefreshHook(publisher, jdbc, lifecycleManager);
    }

    private static Map<String, Object> value(String sourceType, String sourceId) {
        return Map.of("picklistSourceType", sourceType, "picklistSourceId", sourceId, "value", "a");
    }

    @SuppressWarnings("unchecked")
    private List<PlatformEvent<CollectionChangedPayload>> published(String subject) {
        ArgumentCaptor<PlatformEvent<CollectionChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher).publish(eq(subject), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a FIELD value refreshes its field's collection on every pod and locally")
    void fieldValueRefreshesItsCollection() {
        when(jdbc.queryForList(PicklistValueRefreshHook.SELECT_FIELD_COLLECTION, "field-1"))
                .thenReturn(List.of(Map.of("id", "col-1", "name", "orders")));

        hook.afterCreate(value("FIELD", "field-1"), TENANT);

        PlatformEvent<CollectionChangedPayload> event =
                published(CollectionConfigEventPublisher.SUBJECT_PREFIX + "col-1").get(0);
        assertThat(event.getTenantId()).isEqualTo(TENANT);
        assertThat(event.getPayload().getName()).isEqualTo("orders");
        verify(lifecycleManager).refreshOrInitializeLocally("col-1");
    }

    @Test
    @DisplayName("a GLOBAL value refreshes every collection bound to the global picklist")
    void globalValueRefreshesEveryBoundCollection() {
        when(jdbc.queryForList(PicklistValueRefreshHook.SELECT_GLOBAL_PICKLIST_COLLECTIONS, TENANT, "gp-1"))
                .thenReturn(List.of(Map.of("id", "col-1", "name", "orders"), Map.of("id", "col-2", "name", "leads")));

        hook.afterUpdate("pv-1", value("GLOBAL", "gp-1"), value("GLOBAL", "gp-1"), TENANT);

        verify(publisher).publish(eq(CollectionConfigEventPublisher.SUBJECT_PREFIX + "col-1"), any());
        verify(publisher).publish(eq(CollectionConfigEventPublisher.SUBJECT_PREFIX + "col-2"), any());
        verify(lifecycleManager).refreshOrInitializeLocally("col-1");
        verify(lifecycleManager).refreshOrInitializeLocally("col-2");
    }

    @Test
    @DisplayName("a delete resolves the collection before the row is gone and publishes after")
    void deleteResolvesBeforeAndPublishesAfter() {
        when(jdbc.queryForList(PicklistValueRefreshHook.SELECT_VALUE_SOURCE, "pv-1"))
                .thenReturn(List.of(Map.of("picklist_source_type", "FIELD", "picklist_source_id", "field-1")));
        when(jdbc.queryForList(PicklistValueRefreshHook.SELECT_FIELD_COLLECTION, "field-1"))
                .thenReturn(List.of(Map.of("id", "col-1", "name", "orders")));

        assertThat(hook.beforeDelete("pv-1", TENANT).isSuccess()).isTrue();
        verify(publisher, never()).publish(anyString(), any());

        hook.afterDelete("pv-1", TENANT);
        hook.afterDelete("pv-1", TENANT);

        verify(publisher, times(1)).publish(eq(CollectionConfigEventPublisher.SUBJECT_PREFIX + "col-1"), any());
    }

    @Test
    @DisplayName("a value whose source resolves to no collection publishes nothing")
    void unresolvedSourcePublishesNothing() {
        when(jdbc.queryForList(PicklistValueRefreshHook.SELECT_FIELD_COLLECTION, "gone")).thenReturn(List.of());

        hook.afterCreate(value("FIELD", "gone"), TENANT);

        verify(publisher, never()).publish(anyString(), any());
        verify(lifecycleManager, never()).refreshOrInitializeLocally(anyString());
    }
}
