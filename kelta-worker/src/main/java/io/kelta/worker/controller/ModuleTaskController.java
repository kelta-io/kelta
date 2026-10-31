package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.interceptor.SelfScopedController;
import io.kelta.worker.module.ModuleTaskService;
import io.kelta.worker.module.ModuleTaskService.Heartbeat;
import io.kelta.worker.module.ModuleTaskService.RefusedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An external module completes the task of a long-running action, or keeps it alive, with the
 * {@code Kelta-Access-Token} the platform sent it ({@code specs/module-platform/9-external-modules.md}
 * §3.6.2):
 *
 * <ul>
 *   <li>{@code POST /api/modules/self/tasks/complete} with an {@code ActionResponse} and the task's
 *       token ({@code {token, success, output, error}}) → {@code 202}: recorded, and the parked
 *       flow step resumes with it.</li>
 *   <li>{@code POST /api/modules/self/tasks/heartbeat} with {@code {token}} → {@code 200}
 *       {@code {accessToken, accessTokenExpiresAt, taskExpiresAt}}: the task is still pending,
 *       and here is a service-principal token to keep working with.</li>
 * </ul>
 *
 * <p>The token rides the body, never the path: request paths are recorded (span attributes and
 * names, the gateway's audit log on a refusal, ingress access logs), and a body is recorded only
 * after {@code SensitiveBodyRedactor} replaced its {@code token}.
 *
 * <p><b>Authorization</b> is {@link ModuleTaskService}'s, exactly as the credential read's
 * ({@link ModuleCredentialController}): the gateway's {@code static-modules} route lets any
 * {@code API_ACCESS} holder through, and only the install's own service principal, through the
 * gateway-stamped {@code X-Connected-App-Id}, gets further. A missing, unknown, foreign, reused or
 * expired token is one answer, {@code 404 TASK_NOT_FOUND}; the token is never echoed or logged.
 * Every answer is {@code no-store}. A {@link SelfScopedController}: nothing here is a record.
 *
 * <p>Mapped as literal paths, never {@code /**}: {@code DynamicCollectionRouter}'s four-segment
 * mappings would otherwise answer them ({@code ModuleTaskRouteMappingTest}). Not
 * conditional on module runtime support, so a module never meets a 404 it cannot explain; without
 * it every caller is refused.
 */
@RestController
@RequestMapping("/api/modules/self/tasks")
public class ModuleTaskController implements SelfScopedController {

    private final ObjectProvider<ModuleTaskService> tasks;

    public ModuleTaskController(ObjectProvider<ModuleTaskService> tasks) {
        this.tasks = tasks;
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = ModuleCredentialController.CONNECTED_APP_HEADER, required = false)
            String connectedAppId,
            @RequestBody(required = false) byte[] body) {
        ModuleTaskService service = tasks.getIfAvailable();
        if (service == null) {
            return notModulePrincipal();
        }
        try {
            service.complete(tenantId, connectedAppId, callerUserId(), body);
        } catch (RefusedException e) {
            return refusal(e);
        }
        return noStore(HttpStatus.ACCEPTED, null);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = ModuleCredentialController.CONNECTED_APP_HEADER, required = false)
            String connectedAppId,
            @RequestBody(required = false) byte[] body) {
        ModuleTaskService service = tasks.getIfAvailable();
        if (service == null) {
            return notModulePrincipal();
        }
        Heartbeat heartbeat;
        try {
            heartbeat = service.heartbeat(tenantId, connectedAppId, callerUserId(), body);
        } catch (RefusedException e) {
            return refusal(e);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("accessToken", heartbeat.accessToken());
        answer.put("accessTokenExpiresAt", heartbeat.accessTokenExpiresAt().toString());
        answer.put("taskExpiresAt", heartbeat.taskExpiresAt().toString());
        return noStore(HttpStatus.OK, answer);
    }

    private static String callerUserId() {
        return CallerContext.current().map(CallerContext::userId).orElse(null);
    }

    private static ResponseEntity<Map<String, Object>> notModulePrincipal() {
        return error(HttpStatus.FORBIDDEN, "NOT_MODULE_PRINCIPAL",
                "Only an external module's service principal can complete module tasks");
    }

    private static ResponseEntity<Map<String, Object>> refusal(RefusedException e) {
        // Literal codes, one per refusal: they are the documented contract (api/errors.md).
        return switch (e.refusal()) {
            case NOT_MODULE_PRINCIPAL -> error(HttpStatus.FORBIDDEN, "NOT_MODULE_PRINCIPAL", e.getMessage());
            case MODULE_NOT_ACTIVE -> error(HttpStatus.FORBIDDEN, "MODULE_NOT_ACTIVE", e.getMessage());
            case INVALID_TASK_RESULT -> error(HttpStatus.BAD_REQUEST, "INVALID_TASK_RESULT", e.getMessage());
            case TASK_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", e.getMessage());
            case TOKEN_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_UNAVAILABLE", e.getMessage());
        };
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String detail) {
        return noStore(status, JsonApiResponseBuilder.error(String.valueOf(status.value()), code,
                status.getReasonPhrase(), detail));
    }

    private static ResponseEntity<Map<String, Object>> noStore(HttpStatus status, Map<String, Object> body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff");
        if (body == null) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
