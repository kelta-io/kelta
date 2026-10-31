package io.kelta.worker.module;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.service.ModuleServicePrincipalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An external module completes, or keeps alive, the task of a long-running action
 * ({@code specs/module-platform/9-external-modules.md} §3.6.2):
 * {@code POST /api/modules/self/tasks/{token}} and {@code POST /api/modules/self/tasks/{token}/heartbeat}.
 * Security-sensitive: a completion resumes a tenant's flow with data the module supplies. Every
 * check runs in this order, and any failure is a refusal:
 *
 * <ol>
 *   <li><b>The caller is a module's service principal</b> ({@link ModuleSelfPrincipal}, the rule
 *       the credential read applies): {@link Refusal#NOT_MODULE_PRINCIPAL}.</li>
 *   <li><b>The install is callable</b> ({@code ACTIVE} or {@code DEGRADED}, read from the row):
 *       a disabled module affects nothing more, {@link Refusal#MODULE_NOT_ACTIVE}.</li>
 *   <li><b>The result is a valid {@code ActionResponse}</b>, under the limits of a synchronous
 *       answer ({@link RemoteActionHandler#actionResult}: {@code success} a boolean, {@code output} an
 *       object of at most {@link RemoteActionHandler#MAX_OUTPUT_KEYS} keys, a body of at most
 *       {@link ModuleHttpClient#MAX_RESPONSE_BYTES} bytes): {@link Refusal#INVALID_TASK_RESULT}.
 *       Checked before the token, so a module can correct its body and retry.</li>
 *   <li><b>The token is a {@code PENDING}, unexpired task of this tenant and install</b>
 *       ({@link ModuleAsyncTaskStore#complete}): an unknown, foreign, reused or expired token is
 *       {@link Refusal#TASK_NOT_FOUND}, the same answer for each, so a caller learns nothing about
 *       tokens it does not hold.</li>
 * </ol>
 *
 * <p>Then the outcome is recorded (one conditional update, which is what makes the token single
 * use) and the parked step is resumed through {@link FlowEngine#resumeAwaitedTask}: a database
 * claim, so it does not matter which pod receives the completion. If the step has not parked yet,
 * the park finds the outcome.
 *
 * <p>A heartbeat runs checks 1, 2 and 4 without consuming the token, records
 * {@code last_heartbeat_at}, and answers a service-principal access token valid for at least
 * {@link #HEARTBEAT_TOKEN_VALIDITY}: the token a module sends with its calls lasts 15 minutes, and
 * a task can run for hours. A module renews it this way while its task is pending, and only then.
 */
public class ModuleTaskService {

    private static final Logger log = LoggerFactory.getLogger(ModuleTaskService.class);

    /** A heartbeat's access token stays valid at least this long. */
    static final Duration HEARTBEAT_TOKEN_VALIDITY = Duration.ofMinutes(10);

    /** Why a completion or heartbeat was refused. */
    public enum Refusal {
        /** The caller is not an external module install's active service principal. */
        NOT_MODULE_PRINCIPAL,
        /** The caller's install is not {@code ACTIVE} or {@code DEGRADED}. */
        MODULE_NOT_ACTIVE,
        /** The body is not an {@code ActionResponse} within a synchronous answer's limits. */
        INVALID_TASK_RESULT,
        /** No pending, unexpired task of this install has this token. */
        TASK_NOT_FOUND,
        /** A heartbeat's access token could not be had from kelta-auth. */
        TOKEN_UNAVAILABLE
    }

    /** A refused call. The message names the reason and never a token. */
    public static final class RefusedException extends RuntimeException {
        private final Refusal refusal;

        public RefusedException(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * A heartbeat's answer.
     *
     * @param accessToken          a fresh service-principal token for the module's calls
     * @param accessTokenExpiresAt when it expires
     * @param taskExpiresAt        when the task expires
     */
    public record Heartbeat(String accessToken, Instant accessTokenExpiresAt, Instant taskExpiresAt) {
        /** Never prints the token. */
        @Override
        public String toString() {
            return "Heartbeat{accessTokenExpiresAt=" + accessTokenExpiresAt + ", taskExpiresAt=" + taskExpiresAt + '}';
        }
    }

    private final ModuleStore moduleStore;
    private final ModuleServicePrincipalService principals;
    private final ModuleAsyncTaskStore tasks;
    private final ModuleAccessTokenService tokens;
    private final FlowEngine flowEngine;
    private final ObjectMapper objectMapper;

    public ModuleTaskService(ModuleStore moduleStore, ModuleServicePrincipalService principals,
                             ModuleAsyncTaskStore tasks, ModuleAccessTokenService tokens, FlowEngine flowEngine,
                             ObjectMapper objectMapper) {
        this.moduleStore = Objects.requireNonNull(moduleStore);
        this.principals = Objects.requireNonNull(principals);
        this.tasks = Objects.requireNonNull(tasks);
        this.tokens = Objects.requireNonNull(tokens);
        this.flowEngine = Objects.requireNonNull(flowEngine);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Completes the task {@code token} with the module's {@code ActionResponse} and resumes its step.
     *
     * @param tenantId       the request's tenant
     * @param connectedAppId the token's {@code connected_app_id}, or null
     * @param callerUserId   the caller's resolved {@code platform_user.id}, or null
     * @param token          the task token from the path
     * @param body           the request body
     * @throws RefusedException when any check fails, before anything is recorded
     */
    public void complete(String tenantId, String connectedAppId, String callerUserId, String token, byte[] body) {
        TenantModuleData install = callingInstall(tenantId, connectedAppId, callerUserId);
        ActionResult result = parse(body);
        String resumeKey = tasks.complete(tenantId, install.id(), token, ModuleAsyncTaskStore.outcomeOf(
                        result.successful(), result.outputData(), result.errorMessage()))
                .orElseThrow(() -> refuse(Refusal.TASK_NOT_FOUND, tenantId, install,
                        "No pending task of this module has that token"));
        log.info("Module '{}' of tenant {} completed task {} ({})", install.moduleId(), tenantId, resumeKey,
                result.successful() ? "success" : "failure");
        try {
            flowEngine.resumeAwaitedTask(tenantId, resumeKey);
        } catch (RuntimeException e) {
            // Recorded: the step's own park, or the resume poller at the task's expiry, settles it.
            log.warn("Could not resume the step of task {} of tenant {} now: {}", resumeKey, tenantId, e.getMessage());
        }
    }

    /**
     * Keeps the task {@code token} alive and renews the module's access token.
     *
     * @throws RefusedException when any check fails
     */
    public Heartbeat heartbeat(String tenantId, String connectedAppId, String callerUserId, String token) {
        TenantModuleData install = callingInstall(tenantId, connectedAppId, callerUserId);
        Instant taskExpiresAt = tasks.heartbeat(tenantId, install.id(), token)
                .orElseThrow(() -> refuse(Refusal.TASK_NOT_FOUND, tenantId, install,
                        "No pending task of this module has that token"));
        ModuleAccessTokenService.AccessToken accessToken;
        try {
            accessToken = tokens.accessToken(install, HEARTBEAT_TOKEN_VALIDITY);
        } catch (RuntimeException e) {
            throw refuse(Refusal.TOKEN_UNAVAILABLE, tenantId, install,
                    "A new access token could not be issued; retry the heartbeat");
        }
        return new Heartbeat(accessToken.token(), accessToken.expiresAt(), taskExpiresAt);
    }

    private TenantModuleData callingInstall(String tenantId, String connectedAppId, String callerUserId) {
        TenantModuleData install = ModuleSelfPrincipal.resolve(moduleStore, principals, tenantId, connectedAppId,
                callerUserId).orElseThrow(() -> refuse(Refusal.NOT_MODULE_PRINCIPAL, tenantId, null,
                "Only an external module's service principal can complete module tasks"));
        if (!ExternalModuleInvoker.callable(install)) {
            throw refuse(Refusal.MODULE_NOT_ACTIVE, tenantId, install,
                    "Module '" + install.moduleId() + "' is " + install.status() + ", not active");
        }
        return install;
    }

    /** The body as a synchronous {@code ActionResponse} would be read, with the same limits. */
    private ActionResult parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new RefusedException(Refusal.INVALID_TASK_RESULT, "The body must be an ActionResponse object");
        }
        if (body.length > ModuleHttpClient.MAX_RESPONSE_BYTES) {
            throw new RefusedException(Refusal.INVALID_TASK_RESULT, "The body is larger than "
                    + ModuleHttpClient.MAX_RESPONSE_BYTES + " bytes");
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new RefusedException(Refusal.INVALID_TASK_RESULT, "The body is not JSON");
        }
        if (json == null || !json.isObject()) {
            throw new RefusedException(Refusal.INVALID_TASK_RESULT, "The body is not a JSON object");
        }
        try {
            return RemoteActionHandler.actionResult(objectMapper, json);
        } catch (ExternalModuleInvoker.InvalidResponseException e) {
            throw new RefusedException(Refusal.INVALID_TASK_RESULT, e.getMessage());
        }
    }

    private static RefusedException refuse(Refusal refusal, String tenantId, TenantModuleData install,
                                           String message) {
        log.warn("Refused a module task call in tenant {} ({}, module {})", tenantId, refusal,
                install == null ? "none" : install.moduleId());
        return new RefusedException(refusal, message);
    }
}
