package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowExecutionData;
import io.kelta.runtime.flow.FlowStepLogData;
import io.kelta.runtime.flow.FlowStore;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.worker.repository.FlowRepository;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every GET the dedicated flow controllers serve under {@code /api/flows}, routed through Spring
 * MVC the way the gateway forwards it ({@code API_ACCESS} only). Each URL must land on its guarded
 * handler and refuse a portal member someone else's data; the router stand-in is registered
 * because {@code DynamicCollectionRouter}'s all-variable GETs at depths 1-4 would otherwise answer
 * these paths unguarded, and a controller test alone cannot see that (CLAUDE.md pitfall).
 */
@DisplayName("Flow reads vs the generic collection router")
@WithTenant("tenant-1")
class FlowReadRouteMappingTest {

    private static final String STARTER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_MEMBER = "22222222-2222-2222-2222-222222222222";

    /** Stands in for {@code DynamicCollectionRouter}'s GET mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}")
        String one(@PathVariable String collectionName) {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String two(@PathVariable String collectionName, @PathVariable String id) {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String three(@PathVariable String parentName, @PathVariable String parentId,
                     @PathVariable String childName) {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String four(@PathVariable String parentName, @PathVariable String parentId,
                    @PathVariable String childName, @PathVariable String childId) {
            return "router";
        }
    }

    private FlowStore flowStore;
    private FlowRepository flowRepository;
    private ScheduledJobRepository scheduledJobRepository;

    @BeforeEach
    void setUp() {
        flowStore = mock(FlowStore.class);
        flowRepository = mock(FlowRepository.class);
        scheduledJobRepository = mock(ScheduledJobRepository.class);
        when(flowStore.loadExecution("e-1")).thenReturn(Optional.of(new FlowExecutionData("e-1", "tenant-1",
                "f-1", FlowExecutionData.STATUS_COMPLETED, STARTER, null, Map.of("k", "v"), null, null, null, 1,
                10, Map.of(), false, Instant.parse("2026-10-31T00:00:00Z"), null)));
        when(flowStore.loadStepLogs("e-1")).thenReturn(List.of(new FlowStepLogData("s-1", "e-1", "a", "A", "Task",
                FlowStepLogData.STATUS_SUCCEEDED, Map.of(), Map.of(), null, null, 1, 5, null, null)));
        when(flowStore.findExecutionsByFlow(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(flowRepository.findFlowVersions("f-1")).thenReturn(List.of());
        when(flowRepository.findFlowVersion("f-1", 1)).thenReturn(Optional.of(Map.of("id", "v-1")));
        when(flowRepository.findExecutionsByTriggerRecord(anyString(), anyInt()))
                .thenReturn(List.of(Map.of("id", "e-1")));
        when(scheduledJobRepository.findFlowType("f-1", "tenant-1")).thenReturn(Optional.of("SCHEDULED"));
        when(scheduledJobRepository.findScheduleByFlowId("f-1", "tenant-1")).thenReturn(Optional.empty());
        when(scheduledJobRepository.findRecentRunsForFlow(anyString(), anyString(), anyInt())).thenReturn(List.of());
    }

    private MockMvc mvc(AdminPermissionGuard guard) {
        return MockMvcBuilders.standaloneSetup(
                new FlowExecutionController(mock(FlowEngine.class), flowStore, mock(InitialStateBuilder.class),
                        flowRepository, mock(FlowActorResolver.class), guard),
                new FlowScheduleStatusController(scheduledJobRepository, guard),
                new RouterStub()).build();
    }

    private ResultActions getAs(MockMvc mvc, String callerId, CallerContext.UserType type, String path)
            throws Exception {
        CallerContext caller = new CallerContext(callerId, type, false, false);
        try {
            return CallerContext.callAs(caller, () -> {
                try {
                    return mvc.perform(get(path)
                            .header("X-Tenant-ID", "tenant-1")
                            .header("X-User-Profile-Id", AdminPermissionGuardFixture.PROFILE_ID));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            throw (Exception) e.getCause();
        }
    }

    private ResultActions memberGet(String callerId, String path) throws Exception {
        return getAs(mvc(AdminPermissionGuardFixture.apiAccessOnly()), callerId, CallerContext.UserType.PORTAL,
                path);
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/api/flows/executions/e-1", "/api/flows/executions/e-1/steps"})
    @DisplayName("another member's execution and its steps are a 404")
    void otherMembersExecutionIsNotFound(String path) throws Exception {
        memberGet(OTHER_MEMBER, path).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the member who started the execution reads it and its steps")
    void starterReadsOwnExecution() throws Exception {
        memberGet(STARTER, "/api/flows/executions/e-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("flow-executions"))
                .andExpect(jsonPath("$.data.attributes.status").value("COMPLETED"));
        memberGet(STARTER, "/api/flows/executions/e-1/steps")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.executionId").value("e-1"));
    }

    @Test
    @DisplayName("a member's record-executions is only their own, never the unfiltered list")
    void recordExecutionsScopedToCaller() throws Exception {
        memberGet(OTHER_MEMBER, "/api/flows/record-executions?recordId=r-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {
            "/api/flows/f-1/flow-executions",
            "/api/flows/f-1/versions",
            "/api/flows/f-1/versions/1",
            "/api/flows/f-1/schedule",
            "/api/flows/f-1/runs"
    })
    @DisplayName("setup reads are 403 for a member, 200 from the flow controllers for VIEW_SETUP")
    void setupReadsNeedAFlowPermission(String path) throws Exception {
        memberGet(STARTER, path).andExpect(status().isForbidden());

        getAs(mvc(AdminPermissionGuardFixture.granting("VIEW_SETUP")), OTHER_MEMBER,
                CallerContext.UserType.INTERNAL, path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {
            "/api/flows/executions/e-1",
            "/api/flows/executions/e-1/steps",
            "/api/flows/record-executions?recordId=r-1"
    })
    @DisplayName("MANAGE_WORKFLOWS reads another user's execution through the flow controller")
    void managerReadsAll(String path) throws Exception {
        getAs(mvc(AdminPermissionGuardFixture.granting("MANAGE_WORKFLOWS")), OTHER_MEMBER,
                CallerContext.UserType.INTERNAL, path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
    }
}
