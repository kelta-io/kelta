package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.interceptor.SelfScopedController;
import io.kelta.worker.module.ModuleCredentialService;
import io.kelta.worker.module.ModuleCredentialService.ModuleCredential;
import io.kelta.worker.module.ModuleCredentialService.RefusedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/modules/self/credentials/{name}}: an external module reads a tenant credential
 * its approved manifest declares, with the {@code Kelta-Access-Token} the platform sent it
 * ({@code specs/module-platform/9-external-modules.md} §3.3). The checks and the audit are
 * {@link ModuleCredentialService}'s; this controller maps them to HTTP.
 *
 * <p><b>Authorization.</b> The gateway's {@code static-modules} route lets any {@code API_ACCESS}
 * holder through, which a module's SERVICE principal has; who may read is decided here, from the
 * gateway-stamped {@code X-Connected-App-Id} (the token's {@code connected_app_id}) and the caller
 * {@code CallerContextFilter} resolved. Everyone else — a user token, a PAT, another module's
 * principal — is {@code 403}. It is a {@link SelfScopedController}: the answer is the calling
 * install's own grant, and the body is a plain object with no {@code data} member, so neither
 * record advice touches it.
 *
 * <p><b>The answer</b> is {@code {name, type, secrets, metadata}} with
 * {@code Cache-Control: no-store}. The gateway and the worker never record its body as span data
 * ({@code SensitiveBodyRedactor} skips any path with a {@code credentials} segment), and nothing
 * here logs a value.
 *
 * <p>Mapped as a literal path, never {@code /**}: {@code DynamicCollectionRouter}'s four-variable
 * GET would otherwise read {@code /api/modules/self/credentials/x} as a child record
 * ({@code ModuleCredentialRouteMappingTest}). Not conditional on module runtime support, so a module
 * never meets a 404 for a reason it cannot see; without it every caller is refused.
 */
@RestController
@RequestMapping("/api/modules/self/credentials")
public class ModuleCredentialController implements SelfScopedController {

    /** The token's {@code connected_app_id}, stamped by the gateway's {@code HeaderTransformationFilter}. */
    static final String CONNECTED_APP_HEADER = "X-Connected-App-Id";

    private final ObjectProvider<ModuleCredentialService> credentials;

    public ModuleCredentialController(ObjectProvider<ModuleCredentialService> credentials) {
        this.credentials = credentials;
    }

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> read(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = CONNECTED_APP_HEADER, required = false) String connectedAppId,
            @PathVariable String name,
            @RequestParam(value = "purpose", required = false) String purpose) {
        if (purpose != null && !ModuleCredentialService.PURPOSE_LABEL.matcher(purpose).matches()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "400");
            error.put("code", "INVALID_PARAMETER");
            error.put("title", "Bad Request");
            error.put("detail", "purpose must be 1-64 letters, digits or _ . : -");
            error.put("source", Map.of("parameter", "purpose"));
            return noStore(HttpStatus.BAD_REQUEST, Map.of("errors", List.of(error)));
        }
        ModuleCredentialService service = credentials.getIfAvailable();
        if (service == null) {
            return refusal(HttpStatus.FORBIDDEN, "NOT_MODULE_PRINCIPAL",
                    "Only an external module's service principal can read module credentials");
        }
        String callerUserId = CallerContext.current().map(CallerContext::userId).orElse(null);
        ModuleCredential credential;
        try {
            credential = service.read(tenantId, connectedAppId, callerUserId, name, purpose);
        } catch (RefusedException e) {
            // Literal codes, one per refusal: they are the documented contract (api/errors.md).
            return switch (e.refusal()) {
                case NOT_MODULE_PRINCIPAL -> refusal(HttpStatus.FORBIDDEN, "NOT_MODULE_PRINCIPAL", e.getMessage());
                case MODULE_NOT_ACTIVE -> refusal(HttpStatus.FORBIDDEN, "MODULE_NOT_ACTIVE", e.getMessage());
                case CREDENTIAL_NOT_DECLARED ->
                        refusal(HttpStatus.FORBIDDEN, "CREDENTIAL_NOT_DECLARED", e.getMessage());
                case CREDENTIAL_NOT_FOUND -> refusal(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", e.getMessage());
                case CREDENTIALS_UNAVAILABLE ->
                        refusal(HttpStatus.SERVICE_UNAVAILABLE, "CREDENTIALS_UNAVAILABLE", e.getMessage());
            };
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", credential.name());
        body.put("type", credential.type());
        body.put("secrets", credential.secrets());
        body.put("metadata", credential.metadata());
        return noStore(HttpStatus.OK, body);
    }

    private static ResponseEntity<Map<String, Object>> refusal(HttpStatus status, String code, String detail) {
        return noStore(status, JsonApiResponseBuilder.error(String.valueOf(status.value()), code,
                status.getReasonPhrase(), detail));
    }

    private static ResponseEntity<Map<String, Object>> noStore(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
