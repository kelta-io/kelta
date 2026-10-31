package io.kelta.worker.service;

import io.kelta.runtime.workflow.ActorSource;
import io.kelta.worker.service.FlowActorResolver.Resolved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("FlowActorResolver")
class FlowActorResolverTest {

    private static final String TENANT = "5dc71a70-b75e-4034-830f-b18ebf834945";
    private static final String FLOW = "9c882ca4-d4c3-43fe-9252-603cfa4f4c6d";
    private static final String INITIATOR = "11111111-2222-3333-4444-555555555555";
    private static final String RUN_AS = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OWNER = "99999999-8888-7777-6666-555555555555";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcUserIdResolver userIdResolver = mock(JdbcUserIdResolver.class);
    private final FlowActorResolver resolver = new FlowActorResolver(jdbcTemplate, userIdResolver);

    private void stubFlowRow(String runAs, String owner) {
        Map<String, Object> row = new HashMap<>();
        row.put("run_as_user_id", runAs);
        row.put("created_by", owner);
        when(jdbcTemplate.queryForList(anyString(), eq(FLOW))).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("a UUID initiating user wins without touching the database, as an INITIATOR")
    void initiatorWins() {
        assertEquals(new Resolved(INITIATOR, ActorSource.INITIATOR), resolver.resolve(TENANT, FLOW, INITIATOR));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("non-UUID initiator (legacy 'webhook') falls through to the flow row")
    void nonUuidInitiatorFallsThrough() {
        when(userIdResolver.resolve("webhook", TENANT)).thenReturn("webhook");
        stubFlowRow(RUN_AS, OWNER);
        assertEquals(new Resolved(RUN_AS, ActorSource.RUN_AS), resolver.resolve(TENANT, FLOW, "webhook"));
    }

    @Test
    @DisplayName("an email initiator translates to its platform-user UUID, still an INITIATOR")
    void emailInitiatorTranslates() {
        when(userIdResolver.resolve("admin@kelta.local", TENANT)).thenReturn(INITIATOR);
        assertEquals(new Resolved(INITIATOR, ActorSource.INITIATOR),
            resolver.resolve(TENANT, FLOW, "admin@kelta.local"));
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(FLOW));
    }

    @Test
    @DisplayName("no initiator → configured run-as user, as RUN_AS")
    void runAsUser() {
        stubFlowRow(RUN_AS, OWNER);
        assertEquals(new Resolved(RUN_AS, ActorSource.RUN_AS), resolver.resolve(TENANT, FLOW, null));
    }

    @Test
    @DisplayName("no initiator, no run-as → flow owner, as OWNER")
    void ownerFallback() {
        stubFlowRow(null, OWNER);
        assertEquals(new Resolved(OWNER, ActorSource.OWNER), resolver.resolve(TENANT, FLOW, null));
    }

    @Test
    @DisplayName("nothing resolves → NONE")
    void nothingResolves() {
        stubFlowRow(null, null);
        assertEquals(Resolved.NONE, resolver.resolve(TENANT, FLOW, null));
    }

    @Test
    @DisplayName("unknown flow → NONE")
    void unknownFlow() {
        when(jdbcTemplate.queryForList(anyString(), eq(FLOW))).thenReturn(List.of());
        assertEquals(Resolved.NONE, resolver.resolve(TENANT, FLOW, null));
    }

    @Test
    @DisplayName("a database error never blocks the flow start — resolves to NONE")
    void databaseErrorFallsBackToNull() {
        when(jdbcTemplate.queryForList(anyString(), eq(FLOW)))
            .thenThrow(new RuntimeException("connection refused"));
        assertEquals(Resolved.NONE, resolver.resolve(TENANT, FLOW, null));
    }

    @Test
    @DisplayName("a retry keeps the original run's user and recorded source, never turning a fallback into an initiator")
    void retryKeepsTheRecordedSource() {
        assertEquals(new Resolved(OWNER, ActorSource.OWNER),
            resolver.resolveRetry(TENANT, FLOW, OWNER, ActorSource.OWNER));
        assertEquals(new Resolved(INITIATOR, ActorSource.INITIATOR),
            resolver.resolveRetry(TENANT, FLOW, INITIATOR, ActorSource.INITIATOR));
        verifyNoInteractions(jdbcTemplate, userIdResolver);
    }

    @Test
    @DisplayName("a retry of a run with no recorded source keeps its user, with the source still unknown")
    void retryOfAnUnrecordedRun() {
        assertEquals(new Resolved(RUN_AS, null), resolver.resolveRetry(TENANT, FLOW, RUN_AS, null));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("a retry of a run with no user resolves afresh, as a run with no initiator")
    void retryOfARunWithoutUser() {
        stubFlowRow(null, OWNER);
        assertEquals(new Resolved(OWNER, ActorSource.OWNER), resolver.resolveRetry(TENANT, FLOW, null, null));
    }
}
