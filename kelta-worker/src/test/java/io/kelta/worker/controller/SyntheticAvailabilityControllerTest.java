package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.availability.AvailabilityMatchService.ClaimedAlert;
import io.kelta.worker.service.availability.SyntheticAvailabilityService;
import io.kelta.worker.service.availability.SyntheticAvailabilityService.Injection;
import io.kelta.worker.service.availability.SyntheticAvailabilityService.Status;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SyntheticAvailabilityController")
@WithTenant("t1")
class SyntheticAvailabilityControllerTest {

    private static final String TENANT = "t1";
    private static final String PATH = "/api/admin/availability/synthetic-events";

    private SyntheticAvailabilityService syntheticService;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        syntheticService = mock(SyntheticAvailabilityService.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new SyntheticAvailabilityController(
                syntheticService, permissionResolver, bootstrapRepository)).build();
        when(permissionResolver.getProfileId(any())).thenReturn("profile-1");
    }

    private void profileGrants(String... permissions) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(
                java.util.Arrays.stream(permissions)
                        .map(p -> Map.<String, Object>of("permission_name", p, "granted", Boolean.TRUE))
                        .toList());
    }

    private ResultActions inject(String userType, String body) throws Exception {
        return mvc.perform(post(PATH)
                .header("X-User-Id", "user-1")
                .header("X-User-Type", userType)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static WatchTarget target(boolean synthetic) {
        return new WatchTarget("target-1", TENANT, "canary", "probe-1", "Probe", null, "{}",
                true, synthetic);
    }

    private static ClaimedAlert alert(String id) {
        Watch watch = new Watch("w-" + id, TENANT, "m-" + id, "target-1", "{}", "[\"push\"]",
                Watch.STATUS_ACTIVE, null);
        return new ClaimedAlert(id, watch, target(true), "slot-1", null, null, Instant.now());
    }

    @Test
    @DisplayName("an internal MANAGE_DATA caller injects into a synthetic target (201)")
    void injectsForAdmin() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);
        when(syntheticService.inject(eq(TENANT), eq("target-1"), isNull(), isNull(), isNull(),
                isNull())).thenReturn(new Injection(Status.INJECTED, target(true), "synthetic-abc",
                List.of(alert("a1"), alert("a2"))));

        inject("INTERNAL", "{\"targetId\":\"target-1\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetId").value("target-1"))
                .andExpect(jsonPath("$.data.slotKey").value("synthetic-abc"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.alerts").value(2))
                .andExpect(jsonPath("$.data.alertIds[0]").value("a1"));
    }

    @Test
    @DisplayName("slotKey, window and quantity are passed through to the service")
    void passesSlotAndWindow() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);
        Instant start = Instant.parse("2026-08-14T00:00:00Z");
        Instant end = Instant.parse("2026-08-16T00:00:00Z");
        when(syntheticService.inject(TENANT, "target-1", "2026-08-14", start, end, 2))
                .thenReturn(new Injection(Status.INJECTED, target(true), "2026-08-14", List.of()));

        inject("INTERNAL", """
                {"targetId":"target-1","slotKey":"2026-08-14","quantity":2,
                 "window":{"start":"2026-08-14T00:00:00Z","end":"2026-08-16T00:00:00Z"}}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slotKey").value("2026-08-14"))
                .andExpect(jsonPath("$.data.alerts").value(0));
        verify(syntheticService).inject(TENANT, "target-1", "2026-08-14", start, end, 2);
    }

    @Test
    @DisplayName("a non-synthetic target is refused with 422 TARGET_NOT_SYNTHETIC")
    void refusesRealTarget() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);
        when(syntheticService.inject(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new Injection(Status.TARGET_NOT_SYNTHETIC, target(false), null, List.of()));

        inject("INTERNAL", "{\"targetId\":\"target-1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].status").value("422"))
                .andExpect(jsonPath("$.errors[0].code").value("TARGET_NOT_SYNTHETIC"));
    }

    @Test
    @DisplayName("an unknown target is refused with 404 TARGET_NOT_FOUND")
    void refusesUnknownTarget() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);
        when(syntheticService.inject(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new Injection(Status.TARGET_NOT_FOUND, null, null, List.of()));

        inject("INTERNAL", "{\"targetId\":\"missing\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("TARGET_NOT_FOUND"));
    }

    @Test
    @DisplayName("an internal caller without MANAGE_DATA is refused with 403 FORBIDDEN")
    void refusesNonAdmin() throws Exception {
        profileGrants(SupportPermissions.VIEW_ALL_DATA, "VIEW_ANALYTICS");

        inject("INTERNAL", "{\"targetId\":\"target-1\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].status").value("403"))
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
        verifyNoInteractions(syntheticService);
    }

    @Test
    @DisplayName("a PORTAL caller is refused with 403 FORBIDDEN even when its profile grants MANAGE_DATA")
    void refusesPortalCaller() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);

        inject("PORTAL", "{\"targetId\":\"target-1\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
        verifyNoInteractions(syntheticService, bootstrapRepository);
    }

    @Test
    @DisplayName("a caller with no profile is refused with 403 FORBIDDEN")
    void refusesCallerWithoutProfile() throws Exception {
        when(permissionResolver.getProfileId(any())).thenReturn(null);

        inject("INTERNAL", "{\"targetId\":\"target-1\"}")
                .andExpect(status().isForbidden());
        verifyNoInteractions(syntheticService);
    }

    @Test
    @DisplayName("a missing targetId is refused with 400 INVALID_PAYLOAD")
    void refusesMissingTarget() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);

        inject("INTERNAL", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PAYLOAD"));
        verifyNoInteractions(syntheticService);
    }

    @Test
    @DisplayName("a malformed window or quantity is refused with 400 INVALID_PAYLOAD")
    void refusesMalformedWindow() throws Exception {
        profileGrants(SupportPermissions.MANAGE_DATA);

        inject("INTERNAL", "{\"targetId\":\"target-1\",\"window\":{\"start\":\"tomorrow\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PAYLOAD"));
        inject("INTERNAL", "{\"targetId\":\"target-1\",\"quantity\":\"two\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PAYLOAD"));
        verifyNoInteractions(syntheticService);
    }
}
