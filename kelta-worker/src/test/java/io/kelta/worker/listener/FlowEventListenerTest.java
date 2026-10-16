package io.kelta.worker.listener;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowTriggerEvaluator;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.TenantSlugResolver;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("FlowEventListener")
class FlowEventListenerTest {

    private FlowEngine flowEngine;
    private FlowTriggerEvaluator triggerEvaluator;
    private InitialStateBuilder initialStateBuilder;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private TenantSlugResolver tenantSlugResolver;
    private CollectionRegistry collectionRegistry;
    private QueryEngine queryEngine;
    private FlowEventListener listener;

    @BeforeEach
    void setUp() {
        flowEngine = mock(FlowEngine.class);
        triggerEvaluator = mock(FlowTriggerEvaluator.class);
        initialStateBuilder = mock(InitialStateBuilder.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        tenantSlugResolver = mock(TenantSlugResolver.class);
        when(tenantSlugResolver.resolveSlug(anyString())).thenReturn(Optional.of("acme-corp"));
        collectionRegistry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        listener = new FlowEventListener(flowEngine, triggerEvaluator, initialStateBuilder,
                jdbcTemplate, objectMapper, tenantSlugResolver,
                org.mockito.Mockito.mock(io.kelta.worker.service.FlowActorResolver.class),
                collectionRegistry, queryEngine);
    }

    @Nested
    @DisplayName("trigger_config parsing")
    class TriggerConfigParsing {

        @Test
        @DisplayName("Should parse plain trigger_config format correctly")
        @SuppressWarnings("unchecked")
        void shouldParsePlainTriggerConfig() throws Exception {
            String plainConfig = """
                {"events": ["CREATED", "UPDATED"], "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(plainConfig);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(true);
            when(initialStateBuilder.buildFromRecordEvent(any(), any(), any())).thenReturn(Map.of());
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("exec-1");

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerEvaluator).matchesRecordTrigger(any(), configCaptor.capture());

            Map<String, Object> capturedConfig = configCaptor.getValue();
            assertThat(capturedConfig.get("collection")).isEqualTo("orders");
            assertThat(capturedConfig.get("events")).isEqualTo(List.of("CREATED", "UPDATED"));
            assertThat(capturedConfig).doesNotContainKey("type");
            assertThat(capturedConfig).doesNotContainKey("value");
        }

        @Test
        @DisplayName("Should unwrap jsonb wrapper format to extract inner trigger_config")
        @SuppressWarnings("unchecked")
        void shouldUnwrapJsonbWrapper() throws Exception {
            String wrappedConfig = """
                {"null": false, "type": "jsonb", "value": "{\\"events\\": [\\"CREATED\\", \\"UPDATED\\"], \\"collection\\": \\"orders\\"}"}
                """;

            stubJdbcWithTriggerConfig(wrappedConfig);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(true);
            when(initialStateBuilder.buildFromRecordEvent(any(), any(), any())).thenReturn(Map.of());
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("exec-1");

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerEvaluator).matchesRecordTrigger(any(), configCaptor.capture());

            Map<String, Object> capturedConfig = configCaptor.getValue();
            assertThat(capturedConfig.get("collection")).isEqualTo("orders");
            assertThat(capturedConfig.get("events")).isEqualTo(List.of("CREATED", "UPDATED"));
            // After unwrapping, the wrapper keys should NOT be present
            assertThat(capturedConfig).doesNotContainKey("type");
            assertThat(capturedConfig).doesNotContainKey("null");
            assertThat(capturedConfig).doesNotContainKey("value");
        }

        @Test
        @DisplayName("Should handle null trigger_config gracefully")
        @SuppressWarnings("unchecked")
        void shouldHandleNullTriggerConfig() throws Exception {
            stubJdbcWithTriggerConfig(null);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerEvaluator).matchesRecordTrigger(any(), configCaptor.capture());

            Map<String, Object> capturedConfig = configCaptor.getValue();
            assertThat(capturedConfig).isEmpty();
        }

        @Test
        @DisplayName("Should handle empty trigger_config gracefully")
        @SuppressWarnings("unchecked")
        void shouldHandleEmptyTriggerConfig() throws Exception {
            stubJdbcWithTriggerConfig("   ");
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerEvaluator).matchesRecordTrigger(any(), configCaptor.capture());

            Map<String, Object> capturedConfig = configCaptor.getValue();
            assertThat(capturedConfig).isEmpty();
        }

        @Test
        @DisplayName("Should not unwrap when type is not jsonb")
        @SuppressWarnings("unchecked")
        void shouldNotUnwrapWhenTypeIsNotJsonb() throws Exception {
            // A config that happens to have a "type" key but with a different value
            String configWithType = """
                {"type": "text", "value": "something", "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(configWithType);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerEvaluator).matchesRecordTrigger(any(), configCaptor.capture());

            Map<String, Object> capturedConfig = configCaptor.getValue();
            // Should keep the original map since type != "jsonb"
            assertThat(capturedConfig.get("type")).isEqualTo("text");
            assertThat(capturedConfig.get("value")).isEqualTo("something");
            assertThat(capturedConfig.get("collection")).isEqualTo("orders");
        }

        @Test
        @DisplayName("Should not unwrap when type is jsonb but value is missing")
        @SuppressWarnings("unchecked")
        void shouldNotUnwrapWhenValueMissing() throws Exception {
            String configWithTypeOnly = """
                {"type": "jsonb", "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(configWithTypeOnly);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
            verify(triggerEvaluator).matchesRecordTrigger(any(), configCaptor.capture());

            Map<String, Object> capturedConfig = configCaptor.getValue();
            assertThat(capturedConfig.get("type")).isEqualTo("jsonb");
            assertThat(capturedConfig.get("collection")).isEqualTo("orders");
        }

        @Test
        @DisplayName("Should handle invalid JSON in wrapper value gracefully")
        @SuppressWarnings("unchecked")
        void shouldHandleInvalidJsonInWrapperValue() throws Exception {
            String wrappedWithBadInner = """
                {"null": false, "type": "jsonb", "value": "not valid json"}
                """;

            stubJdbcWithTriggerConfig(wrappedWithBadInner);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            // Should not throw; the warn log is emitted and an empty config is used
            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED));

            // The trigger evaluator should still be called (with empty map from the catch block)
            verify(triggerEvaluator).matchesRecordTrigger(any(), any());
        }
    }

    @Nested
    @DisplayName("trigger record id propagation")
    class TriggerRecordIdPropagation {

        @Test
        @DisplayName("Should pass the triggering record's ID to flowEngine.startExecution")
        @SuppressWarnings("unchecked")
        void shouldPassRecordIdAsTriggerRecordId() throws Exception {
            String plainConfig = """
                {"events": ["UPDATED"], "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(plainConfig);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(true);
            when(initialStateBuilder.buildFromRecordEvent(any(), any(), any())).thenReturn(Map.of());
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn("exec-1");

            // The helper builds a payload with recordId="rec-1"
            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.UPDATED));

            verify(flowEngine).startExecution(
                    eq("tenant-1"), any(), any(), any(), any(), eq("rec-1"), anyBoolean());
        }
    }

    @Nested
    @DisplayName("tenant slug binding")
    class TenantSlugBinding {

        @Test
        @DisplayName("Should resolve and bind tenant slug so flow execution sees correct schema")
        @SuppressWarnings("unchecked")
        void shouldBindTenantSlugDuringFlowExecution() throws Exception {
            String plainConfig = """
                {"events": ["UPDATED"], "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(plainConfig);
            when(tenantSlugResolver.resolveSlug("tenant-1")).thenReturn(Optional.of("threadline-clothing"));
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(true);
            when(initialStateBuilder.buildFromRecordEvent(any(), any(), any())).thenReturn(Map.of());

            // Capture the slug seen by TenantContext at the moment startExecution is invoked.
            java.util.concurrent.atomic.AtomicReference<String> seenSlug = new java.util.concurrent.atomic.AtomicReference<>();
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> {
                        seenSlug.set(io.kelta.runtime.context.TenantContext.getSlug());
                        return "exec-1";
                    });

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.UPDATED));

            verify(tenantSlugResolver).resolveSlug("tenant-1");
            assertThat(seenSlug.get()).isEqualTo("threadline-clothing");
        }

        @Test
        @DisplayName("Should still execute when slug cannot be resolved (logs warning and falls back)")
        @SuppressWarnings("unchecked")
        void shouldExecuteWithoutSlugWhenUnresolvable() throws Exception {
            String plainConfig = """
                {"events": ["UPDATED"], "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(plainConfig);
            when(tenantSlugResolver.resolveSlug("tenant-1")).thenReturn(Optional.empty());
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(true);
            when(initialStateBuilder.buildFromRecordEvent(any(), any(), any())).thenReturn(Map.of());
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("exec-1");

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.UPDATED));

            // Execution still attempted — degraded behaviour, but trigger evaluation still happens
            verify(flowEngine).startExecution(eq("tenant-1"), any(), any(), any(), any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("flow config changed broadcast")
    class FlowConfigChanged {

        @Test
        @DisplayName("Should invalidate per-tenant cache when broadcast event arrives")
        @SuppressWarnings("unchecked")
        void shouldInvalidateCacheOnBroadcast() throws Exception {
            String plainConfig = """
                {"events": ["UPDATED"], "collection": "orders"}
                """;
            stubJdbcWithTriggerConfig(plainConfig);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            // Populate the cache via a record event
            String recordEvent = buildRecordChangedMessage("tenant-1", "orders", ChangeType.UPDATED);
            listener.handleRecordChanged(recordEvent);

            // Send a flow config changed broadcast
            String configEvent = """
                {"eventType":"kelta.config.flow.changed","tenantId":"tenant-1",
                 "payload":{"id":"flow-1","changeType":"UPDATED"}}
                """;
            listener.handleFlowConfigChanged(configEvent);

            // Next record event should re-query the DB
            listener.handleRecordChanged(recordEvent);
            verify(jdbcTemplate, times(2)).query(anyString(), any(RowMapper.class), eq("tenant-1"));
        }

        @Test
        @DisplayName("Should silently drop a malformed broadcast message")
        void shouldDropMalformedBroadcast() {
            // Should not throw
            listener.handleFlowConfigChanged("not json");
            // No assertion needed — the test passes if no exception escapes.
        }

        @Test
        @DisplayName("Should drop broadcast with no tenantId")
        void shouldDropBroadcastWithNoTenantId() {
            listener.handleFlowConfigChanged("{\"payload\":{\"id\":\"flow-1\"}}");
            // Cache should be untouched (no tenantId to invalidate).
        }
    }

    @Nested
    @DisplayName("cache management")
    class CacheManagement {

        @Test
        @DisplayName("Should cache flow configs and not re-query on second event")
        @SuppressWarnings("unchecked")
        void shouldCacheFlowConfigs() throws Exception {
            String plainConfig = """
                {"events": ["CREATED"], "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(plainConfig);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            String event = buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED);
            listener.handleRecordChanged(event);
            listener.handleRecordChanged(event);

            // JDBC should only be called once (cached on second call)
            verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class), eq("tenant-1"));
        }

        @Test
        @DisplayName("Should re-query after cache invalidation")
        @SuppressWarnings("unchecked")
        void shouldReQueryAfterCacheInvalidation() throws Exception {
            String plainConfig = """
                {"events": ["CREATED"], "collection": "orders"}
                """;

            stubJdbcWithTriggerConfig(plainConfig);
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(false);

            String event = buildRecordChangedMessage("tenant-1", "orders", ChangeType.CREATED);
            listener.handleRecordChanged(event);

            listener.invalidateCache("tenant-1");

            listener.handleRecordChanged(event);

            // JDBC should be called twice (cache was invalidated between)
            verify(jdbcTemplate, times(2)).query(anyString(), any(RowMapper.class), eq("tenant-1"));
        }
    }

    @Nested
    @DisplayName("omitted payload")
    class OmittedPayload {

        private final String config = """
            {"events": ["UPDATED"], "collection": "orders"}
            """;

        @Test
        @DisplayName("re-reads the record by id and evaluates triggers and initial state on it")
        @SuppressWarnings("unchecked")
        void reReadsRecordWhenPayloadOmitted() throws Exception {
            stubJdbcWithTriggerConfig(config);
            CollectionDefinition orders = mock(CollectionDefinition.class);
            when(collectionRegistry.get("orders")).thenReturn(orders);
            when(queryEngine.getById(orders, "rec-1"))
                    .thenReturn(Optional.of(Map.of("id", "rec-1", "status", "shipped")));
            when(triggerEvaluator.matchesRecordTrigger(any(), any())).thenReturn(true);
            when(initialStateBuilder.buildFromRecordEvent(any(), any(), any())).thenReturn(Map.of());

            listener.handleRecordChanged(buildSlimMessage());

            verify(queryEngine).getById(orders, "rec-1");
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> evaluated =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(triggerEvaluator).matchesRecordTrigger(evaluated.capture(), any());
            assertThat(evaluated.getValue().getPayload().getData()).containsEntry("status", "shipped");
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> built =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(initialStateBuilder).buildFromRecordEvent(built.capture(), eq("flow-1"), anyString());
            assertThat(built.getValue().getPayload().getData()).containsEntry("status", "shipped");
            verify(flowEngine).startExecution(eq("tenant-1"), eq("flow-1"), any(), any(), any(),
                    eq("rec-1"), anyBoolean());
        }

        @Test
        @DisplayName("proceeds with the id-only form when the record can no longer be read")
        @SuppressWarnings("unchecked")
        void proceedsIdOnlyWhenRecordGone() throws Exception {
            stubJdbcWithTriggerConfig(config);
            CollectionDefinition orders = mock(CollectionDefinition.class);
            when(collectionRegistry.get("orders")).thenReturn(orders);
            when(queryEngine.getById(orders, "rec-1")).thenReturn(Optional.empty());

            listener.handleRecordChanged(buildSlimMessage());

            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> evaluated =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(triggerEvaluator).matchesRecordTrigger(evaluated.capture(), any());
            assertThat(evaluated.getValue().getPayload().getData()).isNull();
            assertThat(evaluated.getValue().getPayload().isPayloadOmitted()).isTrue();
        }

        @Test
        @DisplayName("does not re-read events that carry their data")
        void doesNotReReadFullEvents() throws Exception {
            stubJdbcWithTriggerConfig(config);

            listener.handleRecordChanged(buildRecordChangedMessage("tenant-1", "orders", ChangeType.UPDATED));

            verifyNoInteractions(queryEngine);
        }

        private String buildSlimMessage() throws Exception {
            RecordChangedPayload payload = new RecordChangedPayload(
                    "orders", "rec-1", ChangeType.UPDATED, null, null, List.of("status"))
                    .withPayloadOmitted(true);
            return objectMapper.writeValueAsString(EventFactory.createRecordEvent(
                    "record.updated", "tenant-1", "user-1", payload));
        }
    }

    // --- Helper methods ---

    @SuppressWarnings("unchecked")
    private void stubJdbcWithTriggerConfig(String triggerConfigJson) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                .thenAnswer(invocation -> {
                    RowMapper<FlowEventListener.FlowTriggerConfig> mapper = invocation.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("id")).thenReturn("flow-1");
                    when(rs.getString("definition")).thenReturn("{}");
                    when(rs.getString("trigger_config")).thenReturn(triggerConfigJson);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private String buildRecordChangedMessage(String tenantId, String collectionName, ChangeType changeType) throws Exception {
        RecordChangedPayload payload = new RecordChangedPayload(
                collectionName, "rec-1", changeType,
                Map.of("name", "test"), null, List.of());
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record." + changeType.name().toLowerCase(), tenantId, "user-1", payload);
        return objectMapper.writeValueAsString(event);
    }
}
