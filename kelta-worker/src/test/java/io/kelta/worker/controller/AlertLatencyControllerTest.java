package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.repository.AlertDeliveryRepository.DeliveryLatencyRow;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AlertLatencyController")
class AlertLatencyControllerTest {

    private static final String TENANT = "t1";
    private static final Instant CREATED = Instant.parse("2026-08-01T12:00:00Z");

    private AlertDeliveryRepository deliveryRepository;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private AlertLatencyController controller;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(AlertDeliveryRepository.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        controller = new AlertLatencyController(deliveryRepository, permissionResolver,
                bootstrapRepository);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private HttpServletRequest request(String userType, boolean manageData) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Type")).thenReturn(userType);
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions(anyString())).thenReturn(
                manageData
                        ? List.of(Map.of("permission_name", "MANAGE_DATA", "granted", Boolean.TRUE))
                        : List.of());
        return request;
    }

    private HttpServletRequest operator() {
        return request("INTERNAL", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @Test
    @DisplayName("an operator with MANAGE_DATA gets the summary (200)")
    void operatorGetsSummary() {
        when(deliveryRepository.findSentSince(eq(TENANT), any(), isNull())).thenReturn(List.of(
                new DeliveryLatencyRow("a1", "push", "SENT", CREATED, CREATED.plusSeconds(4)),
                new DeliveryLatencyRow("a2", "email", "SENT", CREATED, CREATED.plusSeconds(8))));

        ResponseEntity<Map<String, Object>> response =
                controller.latency("2026-07-01T00:00:00Z", null, operator());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(response);
        assertThat(data).containsEntry("count", 2)
                .containsEntry("p50Seconds", 6.0)
                .containsEntry("since", "2026-07-01T00:00:00Z")
                .containsEntry("channel", null);
        assertThat((Double) data.get("p90Seconds")).isCloseTo(7.6, offset(1e-9));
    }

    @Test
    @DisplayName("a PORTAL actor is denied (403) even with MANAGE_DATA on their profile")
    void portalDenied() {
        assertThatThrownBy(() -> controller.latency(null, null, request("PORTAL", true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    @DisplayName("an internal caller without MANAGE_DATA is denied (403)")
    void withoutPermissionDenied() {
        assertThatThrownBy(() -> controller.latency(null, null, request("INTERNAL", false)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    @DisplayName("since defaults to seven days ago and zero rows is count 0 with null percentiles")
    void defaultsAndEmpty() {
        when(deliveryRepository.findSentSince(anyString(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now().minus(Duration.ofDays(7));

        Map<String, Object> data = data(controller.latency(null, null, operator()));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepository).findSentSince(eq(TENANT), since.capture(), isNull());
        assertThat(since.getValue()).isBetween(before, Instant.now().minus(Duration.ofDays(7)));
        assertThat(data).containsEntry("count", 0)
                .containsEntry("p50Seconds", null)
                .containsEntry("p90Seconds", null);
    }

    @Test
    @DisplayName("a channel is passed through and echoed")
    void channelPassedThrough() {
        when(deliveryRepository.findSentSince(anyString(), any(), eq("email"))).thenReturn(List.of());

        Map<String, Object> data = data(controller.latency(null, "email", operator()));

        assertThat(data).containsEntry("channel", "email");
        verify(deliveryRepository).findSentSince(eq(TENANT), any(), eq("email"));
    }

    @Test
    @DisplayName("an invalid since is 400")
    void invalidSince() {
        assertThatThrownBy(() -> controller.latency("last tuesday", null, operator()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an invalid channel is 400")
    void invalidChannel() {
        assertThatThrownBy(() -> controller.latency(null, "pager", operator()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
