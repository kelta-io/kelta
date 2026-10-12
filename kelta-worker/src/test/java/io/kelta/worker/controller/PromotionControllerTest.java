package io.kelta.worker.controller;

import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.MetadataPromotionService;
import io.kelta.worker.util.WithTenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PromotionController")
@WithTenant("t1")
class PromotionControllerTest {

    private MetadataPromotionService promotionService;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private PromotionController controller;

    @BeforeEach
    void setUp() {
        promotionService = mock(MetadataPromotionService.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        controller = new PromotionController(promotionService, permissionResolver, bootstrapRepository);
    }

    private void grantPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_SANDBOXES", "granted", true)));
    }

    @Test
    @DisplayName("rejects a profile without MANAGE_SANDBOXES")
    void rejectsMissingPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of());

        assertThatThrownBy(() -> controller.listPromotions(50, 0, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(promotionService);
    }

    @Test
    @DisplayName("lists promotions")
    void listsPromotions() {
        grantPermission();
        when(promotionService.listPromotions("t1", 50, 0))
                .thenReturn(List.of(Map.of("id", "promo-1")));

        var response = controller.listPromotions(50, 0, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("getPromotion returns 404 when not found")
    void getReturns404() {
        grantPermission();
        when(promotionService.getPromotion("promo-x", "t1")).thenReturn(Optional.empty());

        var response = controller.getPromotion("promo-x", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("createPromotion requires both environment ids")
    void createRequiresBothEnvIds() {
        grantPermission();
        Map<String, Object> body = new HashMap<>();
        body.put("sourceEnvironmentId", "env-1");

        var response = controller.createPromotion(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(promotionService);
    }

    @Test
    @DisplayName("createPromotion delegates with the gateway-forwarded creator identity")
    void createDelegatesWithCreator() {
        grantPermission();
        when(promotionService.createPromotion("t1", "env-1", "env-2", "FULL", "SKIP", null, "user-1"))
                .thenReturn(Map.of("id", "promo-1", "status", "PENDING"));

        Map<String, Object> body = new HashMap<>();
        body.put("sourceEnvId", "env-1");
        body.put("targetEnvId", "env-2");

        var response = controller.createPromotion(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(promotionService).createPromotion("t1", "env-1", "env-2", "FULL", "SKIP", null, "user-1");
    }

    @Test
    @DisplayName("createPromotion passes SELECTIVE items through")
    void createPassesItems() {
        grantPermission();
        when(promotionService.createPromotion(eq("t1"), eq("env-1"), eq("env-2"),
                eq("SELECTIVE"), eq("OVERWRITE"), anyList(), eq("user-1")))
                .thenReturn(Map.of("id", "promo-1"));

        Map<String, Object> body = new HashMap<>();
        body.put("sourceEnvironmentId", "env-1");
        body.put("targetEnvironmentId", "env-2");
        body.put("promotionType", "SELECTIVE");
        body.put("conflictMode", "OVERWRITE");
        body.put("items", List.of(Map.of("itemType", "COLLECTION", "itemName", "orders")));

        var response = controller.createPromotion(body, request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(promotionService).createPromotion(eq("t1"), eq("env-1"), eq("env-2"),
                eq("SELECTIVE"), eq("OVERWRITE"),
                eq(List.of(Map.of("itemType", "COLLECTION", "itemName", "orders"))), eq("user-1"));
    }

    @Test
    @DisplayName("approvePromotion maps IllegalState (four-eyes violations) to 409")
    void approveMapsIllegalStateTo409() {
        grantPermission();
        when(promotionService.approvePromotion("promo-1", "t1", "user-1"))
                .thenThrow(new IllegalStateException("A promotion cannot be approved by its creator"));

        var response = controller.approvePromotion("promo-1", request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("approvePromotion maps IllegalArgument to 400")
    void approveMapsIllegalArgumentTo400() {
        grantPermission();
        when(promotionService.approvePromotion("promo-x", "t1", "user-1"))
                .thenThrow(new IllegalArgumentException("Promotion not found"));

        var response = controller.approvePromotion("promo-x", request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("executePromotion returns 404 for an unknown promotion")
    void executeReturns404() {
        grantPermission();
        when(promotionService.getPromotion("promo-x", "t1")).thenReturn(Optional.empty());

        var response = controller.executePromotion("promo-x", request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(promotionService, never()).executePromotion(any(), any(), any());
    }

    @Test
    @DisplayName("executePromotion refuses to run a non-APPROVED promotion")
    void executeRequiresApproved() {
        grantPermission();
        when(promotionService.getPromotion("promo-1", "t1"))
                .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "PENDING")));

        var response = controller.executePromotion("promo-1", request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(promotionService, never()).executePromotion(any(), any(), any());
    }

    @Test
    @DisplayName("executePromotion accepts an APPROVED promotion asynchronously")
    void executeAcceptsApproved() {
        grantPermission();
        when(promotionService.getPromotion("promo-1", "t1"))
                .thenReturn(Optional.of(Map.of("id", "promo-1", "status", "APPROVED")));

        var response = controller.executePromotion("promo-1", request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(promotionService).executePromotion("promo-1", "t1", "user-1");
    }

    @Test
    @DisplayName("rollbackPromotion maps guard violations to 409")
    void rollbackMapsIllegalStateTo409() {
        grantPermission();
        when(promotionService.rollbackPromotion("promo-1", "t1", "user-1"))
                .thenThrow(new IllegalStateException("No pre-promotion target snapshot available for rollback"));

        var response = controller.rollbackPromotion("promo-1", request, "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("getPromotionItems returns 404 for a promotion outside the tenant")
    void getItemsReturns404() {
        grantPermission();
        when(promotionService.getPromotion("promo-x", "t1")).thenReturn(Optional.empty());

        var response = controller.getPromotionItems("promo-x", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(promotionService, never()).getPromotionItems(any());
    }
}
