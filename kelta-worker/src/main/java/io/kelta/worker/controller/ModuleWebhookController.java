package io.kelta.worker.controller;

import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleProxy;
import io.kelta.worker.module.RuntimeModuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound webhooks for runtime-installed modules.
 *
 * <p>Spring MVC resolves routes when the application context starts, so a module loaded later
 * cannot contribute a controller of its own. This one generic, platform-owned route is what lets
 * a module accept a webhook from an external system that has no way to know about flows — a
 * payment processor, a delivery service, an upstream poller — by dispatching the raw request to
 * the {@code ActionHandler} the module's manifest names in {@code webhookHandlerKey}.
 *
 * <p><b>This is an unauthenticated path and the platform verifies nothing.</b> The module owns its
 * own trust anchor: it resolves its credential and checks the signature over the raw body before
 * treating the payload as real. The raw body is handed through untouched, because re-serializing
 * would change the bytes an HMAC covers.
 *
 * <p>The {@code tenantId} in the path is <b>untrusted input</b>. It selects which tenant's module
 * — and therefore which secret — to dispatch to, nothing more. An attacker may name any tenant;
 * without that tenant's secret the module's own signature check rejects the request.
 *
 * <p>Answers 404 for every "nothing to dispatch to" case — unknown tenant, module not installed or
 * not active, no webhook handler declared — so an unauthenticated caller cannot tell them apart
 * and enumerate which modules a tenant runs.
 */
@RestController
@RequestMapping("/api/modules/webhooks")
@ConditionalOnBean(RuntimeModuleManager.class)
public class ModuleWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ModuleWebhookController.class);

    private final RuntimeModuleManager runtimeModuleManager;

    /** External modules (part 9d); null when module support is switched off. */
    private ExternalModuleProxy externalModules;

    public ModuleWebhookController(RuntimeModuleManager runtimeModuleManager) {
        this.runtimeModuleManager = runtimeModuleManager;
    }

    /** Injected via setter so the existing constructor stays as it is. */
    @Autowired(required = false)
    public void setExternalModules(ExternalModuleProxy externalModules) {
        this.externalModules = externalModules;
    }

    /**
     * UNAUTHENTICATED path — the module owns verification. The body arrives as bytes so an
     * external module receives exactly what the sender signed; a JAR handler gets it decoded with
     * the request's charset (UTF-8 when it names none).
     */
    @PostMapping("/{tenantId}/{moduleId}")
    public ResponseEntity<Void> receive(@PathVariable String tenantId,
                                        @PathVariable String moduleId,
                                        @RequestHeader Map<String, String> headers,
                                        @RequestBody(required = false) byte[] rawBody) {
        if (externalModules != null && externalModules.isExternal(tenantId, moduleId)) {
            return forward(tenantId, moduleId, rawBody, headers);
        }
        Optional<ActionResult> result;
        try {
            result = runtimeModuleManager.dispatchWebhook(tenantId, moduleId,
                    rawBody == null ? null : new String(rawBody, charset(headers)),
                    ExternalModuleProxy.forwardableHeaders(headers));
        } catch (RuntimeException e) {
            // Module code threw rather than returning a failure result. 500 asks the sender to
            // retry, which is the right answer for an unexpected fault.
            log.error("Module '{}' of tenant {} threw handling a webhook: {}",
                    moduleId, tenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!result.get().successful()) {
            // The handler rejected it — most often a failed signature check. 401 rather than 500:
            // this is not a fault to retry. The handler's message is logged, never returned; it
            // can name internals of whatever system the module talks to.
            log.warn("Module '{}' of tenant {} rejected a webhook: {}",
                    moduleId, tenantId, result.get().errorMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * An external module's webhook: the raw bytes and the allowlisted headers forwarded to
     * {@code {baseUrl}/kelta/v1/webhooks}, and the module's status handed back so the sender
     * retries what the module did not accept. 503 when the module cannot be called, so it retries.
     */
    private ResponseEntity<Void> forward(String tenantId, String moduleId, byte[] rawBody,
                                         Map<String, String> headers) {
        Optional<ExternalModuleProxy.Forwarded> forwarded;
        try {
            forwarded = externalModules.webhook(tenantId, moduleId, rawBody, headers);
        } catch (ModuleUnavailableException e) {
            log.warn("External module '{}' of tenant {} could not take a webhook: {}", moduleId, tenantId,
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return forwarded.map(f -> ResponseEntity.status(f.status()).<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The request body's charset, UTF-8 when the {@code Content-Type} names none (or a bad one). */
    private static Charset charset(Map<String, String> headers) {
        String contentType = headers == null ? null : headers.entrySet().stream()
                .filter(e -> "content-type".equalsIgnoreCase(e.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            return charset == null ? StandardCharsets.UTF_8 : charset;
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
