package io.kelta.worker.controller;

import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Inbound webhooks for modules.
 *
 * <p>A module is another service and cannot contribute a controller of its own. This one generic,
 * platform-owned route is what lets a module accept a webhook from an external system that has no
 * way to know about flows — a payment processor, a delivery service, an upstream poller — by
 * forwarding the raw request to the module's {@code {baseUrl}/kelta/v1/webhooks}
 * ({@link ExternalModuleProxy#webhook}).
 *
 * <p><b>This is an unauthenticated path and the platform verifies nothing.</b> The module owns its
 * own trust anchor: it resolves its credential and checks the signature over the raw body before
 * treating the payload as real. The raw body is handed through untouched, because re-serializing
 * would change the bytes an HMAC covers.
 *
 * <p>The {@code tenantId} in the path is <b>untrusted input</b>. It selects which tenant's module
 * — and therefore which secret — to forward to, nothing more. An attacker may name any tenant;
 * without that tenant's secret the module's own signature check rejects the request.
 *
 * <p>Answers 404 for every "nothing to forward to" case — unknown tenant, module not installed or
 * not callable, no webhook declared — so an unauthenticated caller cannot tell them apart and
 * enumerate which modules a tenant runs.
 *
 * <p>Not gated with {@code @ConditionalOnBean}: on a component-scanned controller that depends on
 * scan order. The controller always exists and answers 404 when module support is switched off.
 */
@RestController
@RequestMapping("/api/modules/webhooks")
public class ModuleWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ModuleWebhookController.class);

    /** Null when module support is switched off ({@code kelta.modules.runtime.enabled=false}). */
    private ExternalModuleProxy externalModules;

    @Autowired(required = false)
    public void setExternalModules(ExternalModuleProxy externalModules) {
        this.externalModules = externalModules;
    }

    /**
     * UNAUTHENTICATED path — the module owns verification. The body arrives as bytes so the module
     * receives exactly what the sender signed, with only the allowlisted headers. The module's
     * status goes back to the sender, so it retries what the module did not accept; 503 when the
     * module cannot be called, so it retries.
     */
    @PostMapping("/{tenantId}/{moduleId}")
    public ResponseEntity<Void> receive(@PathVariable String tenantId,
                                        @PathVariable String moduleId,
                                        @RequestHeader Map<String, String> headers,
                                        @RequestBody(required = false) byte[] rawBody) {
        if (externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        Optional<ExternalModuleProxy.Forwarded> forwarded;
        try {
            forwarded = externalModules.webhook(tenantId, moduleId, rawBody, headers);
        } catch (ModuleUnavailableException e) {
            log.warn("Module '{}' of tenant {} could not take a webhook: {}", moduleId, tenantId,
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return forwarded.map(f -> ResponseEntity.status(f.status()).<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
