package io.kelta.worker.listener;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.runtime.workflow.ActorSource;
import io.kelta.worker.service.TenantSlugResolver;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NatsTriggerFlowListener")
class NatsTriggerFlowListenerTest {

    private static final String TENANT = "tenant-1";
    private static final String DEFINITION = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}";

    @Mock
    private FlowEngine flowEngine;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TenantSlugResolver tenantSlugResolver;

    private NatsTriggerFlowListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        listener = new NatsTriggerFlowListener(flowEngine, new InitialStateBuilder(),
                jdbcTemplate, objectMapper, tenantSlugResolver,
                runAsResolver());
        lenient().when(tenantSlugResolver.resolveSlug(TENANT)).thenReturn(Optional.of("acme"));
    }

    private void mockActiveFlow(String flowId, String topic) {
        when(jdbcTemplate.query(contains("NATS_TRIGGERED"), any(RowMapper.class), eq(TENANT)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getString("id")).thenReturn(flowId);
                    when(rs.getString("definition")).thenReturn(DEFINITION);
                    when(rs.getString("trigger_config")).thenReturn("{\"topic\":\"" + topic + "\"}");
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    @DisplayName("starts a matching flow with the message body as $.input")
    @SuppressWarnings("unchecked")
    void startsMatchingFlow() {
        mockActiveFlow("flow-1", "orders");

        listener.handleTriggerMessage("kelta.trigger.tenant-1.orders", "{\"orderId\":\"o-9\"}");

        ArgumentCaptor<Map<String, Object>> stateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(flowEngine).startExecution(eq(TENANT), eq("flow-1"), eq(DEFINITION),
                stateCaptor.capture(), eq("run-as-1"), eq(ActorSource.RUN_AS), isNull(), eq(false));

        Map<String, Object> state = stateCaptor.getValue();
        Map<String, Object> input = (Map<String, Object>) state.get("input");
        assertThat(input).containsEntry("orderId", "o-9");
        Map<String, Object> trigger = (Map<String, Object>) state.get("trigger");
        assertThat(trigger).containsEntry("type", "NATS_MESSAGE").containsEntry("topic", "orders");
    }

    @Test
    @DisplayName("non-matching topic starts nothing")
    void nonMatchingTopicIgnored() {
        mockActiveFlow("flow-1", "orders");

        listener.handleTriggerMessage("kelta.trigger.tenant-1.invoices", "{}");

        verify(flowEngine, never()).startExecution(anyString(), anyString(), anyString(),
                anyMap(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("multi-token topics match the full remainder of the subject")
    void multiTokenTopicMatches() {
        mockActiveFlow("flow-1", "orders.created");

        listener.handleTriggerMessage("kelta.trigger.tenant-1.orders.created", "{}");

        verify(flowEngine).startExecution(eq(TENANT), eq("flow-1"), eq(DEFINITION),
                anyMap(), eq("run-as-1"), eq(ActorSource.RUN_AS), isNull(), eq(false));
    }

    @Test
    @DisplayName("non-JSON body is wrapped as {raw: ...}")
    @SuppressWarnings("unchecked")
    void nonJsonBodyWrapped() {
        mockActiveFlow("flow-1", "orders");

        listener.handleTriggerMessage("kelta.trigger.tenant-1.orders", "plain text");

        ArgumentCaptor<Map<String, Object>> stateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(flowEngine).startExecution(anyString(), anyString(), anyString(),
                stateCaptor.capture(), any(), any(), any(), anyBoolean());
        Map<String, Object> input = (Map<String, Object>) stateCaptor.getValue().get("input");
        assertThat(input).containsEntry("raw", "plain text");
    }

    @Test
    @DisplayName("malformed subject is dropped without any lookup")
    void malformedSubjectDropped() {
        listener.handleTriggerMessage("kelta.trigger.only-tenant", "{}");
        listener.handleTriggerMessage("some.other.subject", "{}");

        verifyNoInteractions(flowEngine, jdbcTemplate);
    }

    @Test
    @DisplayName("flow config changed broadcast invalidates the tenant cache")
    void configChangeInvalidatesCache() {
        mockActiveFlow("flow-1", "orders");
        listener.handleTriggerMessage("kelta.trigger.tenant-1.orders", "{}");
        // cached — second message must not re-query
        listener.handleTriggerMessage("kelta.trigger.tenant-1.orders", "{}");
        verify(jdbcTemplate, org.mockito.Mockito.times(1))
                .query(contains("NATS_TRIGGERED"), any(RowMapper.class), eq(TENANT));

        listener.handleFlowConfigChanged("{\"tenantId\":\"tenant-1\"}");
        listener.handleTriggerMessage("kelta.trigger.tenant-1.orders", "{}");

        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .query(contains("NATS_TRIGGERED"), any(RowMapper.class), eq(TENANT));
    }

    /** A resolver that answers every flow with {@code run-as-1} as its {@link ActorSource#RUN_AS} actor. */
    private static io.kelta.worker.service.FlowActorResolver runAsResolver() {
        io.kelta.worker.service.FlowActorResolver resolver = org.mockito.Mockito.mock(io.kelta.worker.service.FlowActorResolver.class);
        org.mockito.Mockito.lenient().when(resolver.resolve(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new io.kelta.worker.service.FlowActorResolver.Resolved("run-as-1", ActorSource.RUN_AS));
        return resolver;
    }
}
