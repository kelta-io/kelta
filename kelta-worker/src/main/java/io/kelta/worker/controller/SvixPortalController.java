package io.kelta.worker.controller;

import io.kelta.worker.config.SvixConfig;
import io.kelta.worker.service.SvixTenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Provides Svix authentication credentials for the current tenant so the
 * frontend can render a custom webhook management UI using {@code svix-react}.
 *
 * <p>{@code GET /api/svix/portal} returns a short-lived token, the tenant's
 * Svix application ID, and the Svix server URL.
 *
 * <p>The tenant ID is resolved from the {@code X-Tenant-ID} header set by the gateway.
 *
 * <p>Registered only with the Svix integration ({@code kelta.svix.auth-token} set): its service
 * exists only then, so an unconditional controller would stop the worker booting without Svix.
 * Without it {@code /api/svix/portal} is a 404 and the webhooks page shows its error state.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/svix")
@Conditional(SvixConfig.SvixTokenConfigured.class)
public class SvixPortalController {

    private static final Logger log = LoggerFactory.getLogger(SvixPortalController.class);

    private final SvixTenantService svixTenantService;
    private final String svixServerUrl;

    public SvixPortalController(SvixTenantService svixTenantService,
                                @Value("${kelta.svix.public-url:${kelta.svix.server-url}}") String svixServerUrl) {
        this.svixTenantService = svixTenantService;
        this.svixServerUrl = svixServerUrl;
    }

    @GetMapping("/portal")
    public ResponseEntity<Map<String, String>> getPortalAccess(HttpServletRequest request) {
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("No tenant ID in request — cannot generate Svix portal access");
            return ResponseEntity.badRequest().body(Map.of("error", "Missing tenant context"));
        }

        try {
            svixTenantService.ensureApplication(tenantId, tenantId);
            var access = svixTenantService.getPortalAccess(tenantId);
            return ResponseEntity.ok(Map.of(
                    "token", access.token(),
                    "appId", tenantId,
                    "serverUrl", svixServerUrl
            ));
        } catch (RestClientResponseException e) {
            log.error("Svix returned {} for tenant '{}': {}",
                    e.getStatusCode(), tenantId, e.getResponseBodyAsString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Svix returned " + e.getStatusCode().value()
            ));
        } catch (Exception e) {
            log.error("Failed to generate Svix portal access for tenant '{}'", tenantId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to generate webhook portal access"));
        }
    }
}
