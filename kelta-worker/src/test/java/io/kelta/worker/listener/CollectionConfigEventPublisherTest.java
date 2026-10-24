package io.kelta.worker.listener;

import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.service.FormulaRecomputeService;
import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.VectorMaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("CollectionConfigEventPublisher")
class CollectionConfigEventPublisherTest {

    private PlatformEventPublisher eventPublisher;
    private CollectionLifecycleManager lifecycleManager;
    private CollectionConfigEventPublisher publisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(PlatformEventPublisher.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        publisher = new CollectionConfigEventPublisher(eventPublisher, lifecycleManager);
    }

    @Test
    @DisplayName("Should target 'collections' collection")
    void shouldTargetCollections() {
        assertEquals("collections", publisher.getCollectionName());
    }

    @Test
    @DisplayName("Should have order 100 to run after schema hooks")
    void shouldHaveOrderAfterSchemaHooks() {
        assertEquals(100, publisher.getOrder());
    }

    @Test
    @DisplayName("Should publish CREATED event wrapped in PlatformEvent")
    void shouldPublishCreatedEvent() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-1",
            "name", "orders",
            "displayName", "Orders",
            "active", true,
            "currentVersion", 1
        ));

        publisher.afterCreate(record, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("Should publish UPDATED event wrapped in PlatformEvent")
    void shouldPublishUpdatedEvent() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-1",
            "name", "orders",
            "active", true
        ));
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1",
            "name", "orders"
        ));

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("Should not publish event when record is missing name field")
    void shouldNotPublishEventWhenNameMissing() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-no-name",
            "active", true
        ));
        // name is intentionally missing

        publisher.afterCreate(record, "tenant-1");

        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("Should publish DELETED event wrapped in PlatformEvent")
    void shouldPublishDeletedEvent() {
        publisher.afterDelete("col-1", "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("An ownership change publishes and refreshes this pod synchronously")
    void ownershipChangeRefreshesLocally() {
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "ownerScope", "NONE", "ownerScopeReads", true));
        Map<String, Object> record = new HashMap<>(previous);
        record.put("ownerField", "createdBy");
        record.put("ownerScope", "PORTAL");

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
        verify(lifecycleManager).refreshOrInitializeLocally("col-1");
    }

    @Test
    @DisplayName("Turning off owner-scoped reads refreshes this pod synchronously")
    void ownerScopeReadsChangeRefreshesLocally() {
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "ownerField", "createdBy", "ownerScope", "ALL",
            "ownerScopeReads", true));
        Map<String, Object> record = new HashMap<>(previous);
        record.put("ownerScopeReads", false);

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(lifecycleManager).refreshOrInitializeLocally("col-1");
    }

    @Test
    @DisplayName("An update that leaves ownership alone only publishes")
    void nonOwnershipUpdateDoesNotRefreshLocally() {
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "displayName", "Orders", "ownerScope", "NONE"));
        Map<String, Object> record = new HashMap<>(previous);
        record.put("displayName", "All orders");

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
        verifyNoInteractions(lifecycleManager);
    }

    @Test
    @DisplayName("A record with active=false publishes active:false")
    void deactivatedCollectionPublishesActiveFalse() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "active", false));

        publisher.afterUpdate("col-1", record, new HashMap<>(record), "tenant-1");

        CollectionChangedPayload payload = publishedPayload(eventPublisher);
        assertEquals(Boolean.FALSE, payload.getActive());
        JsonNode json = toJson(payload);
        assertTrue(json.has("active"));
        assertFalse(json.get("active").asBoolean());
    }

    @Test
    @DisplayName("A record without an active value publishes no active key (no default to true)")
    void recordWithoutActivePublishesNoActiveKey() {
        Map<String, Object> record = new HashMap<>(Map.of("id", "col-1", "name", "orders"));

        publisher.afterUpdate("col-1", record, new HashMap<>(record), "tenant-1");

        CollectionChangedPayload payload = publishedPayload(eventPublisher);
        assertNull(payload.getActive());
        assertFalse(toJson(payload).has("active"));
    }

    @Test
    @DisplayName("Field, validation-rule, approval, page-layout, layout-section and UI-page publishers send no active key")
    void otherPublishersSendNoActiveKey() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq("col-1")))
                .thenReturn(List.of(Map.of("name", "orders")));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("col-1")))
                .thenReturn(List.of("orders"));

        Map<String, BeforeSaveHook> hooks = new LinkedHashMap<>();
        hooks.put("fields", new FieldConfigEventPublisher(eventPublisher, jdbcTemplate, lifecycleManager,
                mock(CerbosAuthorizationService.class), mock(FormulaRecomputeService.class),
                mock(SearchIndexService.class), mock(VectorMaintenanceService.class)));
        hooks.put("validation-rules", new ValidationRuleRefreshHook(eventPublisher, jdbcTemplate));
        hooks.put("approval-processes", new ApprovalProcessConfigHook(eventPublisher, jdbcTemplate));
        hooks.put("page-layouts", new PageLayoutConfigEventPublisher(eventPublisher));
        hooks.put("layout-sections", new LayoutSectionRefreshHook(eventPublisher));
        hooks.put("ui-pages", new UIPageConfigEventPublisher(eventPublisher));

        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "rec-1", "name", "Thing", "collectionId", "col-1", "layoutId", "layout-1"));
        for (var entry : hooks.entrySet()) {
            clearInvocations(eventPublisher);

            entry.getValue().afterUpdate("rec-1", record, new HashMap<>(record), "tenant-1");

            CollectionChangedPayload payload = publishedPayload(eventPublisher);
            assertNull(payload.getActive(), entry.getKey());
            assertFalse(toJson(payload).has("active"), entry.getKey());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CollectionChangedPayload publishedPayload(PlatformEventPublisher eventPublisher) {
        ArgumentCaptor<PlatformEvent<CollectionChangedPayload>> captor =
                ArgumentCaptor.forClass((Class) PlatformEvent.class);
        verify(eventPublisher).publish(anyString(), captor.capture());
        return captor.getValue().getPayload();
    }

    private static JsonNode toJson(CollectionChangedPayload payload) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(mapper.writeValueAsString(payload));
    }
}
