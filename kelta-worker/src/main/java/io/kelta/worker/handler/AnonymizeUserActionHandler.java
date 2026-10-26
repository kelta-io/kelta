package io.kelta.worker.handler;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.UserAnonymizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Flow action {@code ANONYMIZE_USER}: erases a user's personal data through
 * {@link UserAnonymizationService}, the same service as {@code POST /api/admin/users/{id}/anonymize}.
 *
 * <p>Parameters:
 * <pre>
 * { "userId": "${$.input.userId}" }
 * </pre>
 *
 * <p>Flows write without an HTTP identity, so the identity-collection guard does not judge them:
 * the step fails unless the flow's actor (the context's {@code userId}) is an active user of the
 * tenant whose profile grants {@code MANAGE_USERS}. The {@code system} user is refused by the
 * service. Output: {@code {"userId": …, "alreadyAnonymized": true|false}}.
 */
public class AnonymizeUserActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(AnonymizeUserActionHandler.class);

    static final String PERMISSION = "MANAGE_USERS";

    private final UserAnonymizationService anonymizationService;
    private final BootstrapRepository bootstrapRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnonymizeUserActionHandler(UserAnonymizationService anonymizationService,
                                      BootstrapRepository bootstrapRepository,
                                      JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper) {
        this.anonymizationService = anonymizationService;
        this.bootstrapRepository = bootstrapRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getActionTypeKey() {
        return "ANONYMIZE_USER";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            String userId = userId(context.actionConfigJson());
            if (userId == null) {
                return ActionResult.failure("ANONYMIZE_USER requires a userId parameter");
            }
            if (context.tenantId() == null || context.tenantId().isBlank()) {
                return ActionResult.failure("ANONYMIZE_USER requires a tenant");
            }
            // Flows may execute asynchronously with no tenant bound: bind the flow's tenant then,
            // and leave an existing binding untouched otherwise.
            if (TenantContext.get() == null) {
                return TenantContext.callWithTenant(context.tenantId(), () -> anonymize(context, userId));
            }
            return anonymize(context, userId);
        } catch (Exception e) {
            log.error("ANONYMIZE_USER action failed: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    private ActionResult anonymize(ActionContext context, String userId) {
        String actorId = context.userId();
        if (!actorHasPermission(context.tenantId(), actorId)) {
            log.warn("ANONYMIZE_USER refused: flow actor {} lacks {} in tenant {}",
                    actorId, PERMISSION, context.tenantId());
            return ActionResult.failure("The flow's user lacks the " + PERMISSION + " permission");
        }
        try {
            UserAnonymizationService.Result result =
                    anonymizationService.anonymize(context.tenantId(), userId, actorId);
            return ActionResult.success(Map.of(
                    "userId", result.userId(),
                    "alreadyAnonymized", result.alreadyAnonymized()));
        } catch (UserAnonymizationService.UserNotFoundException
                 | UserAnonymizationService.AnonymizationRefusedException e) {
            return ActionResult.failure(e.getMessage());
        }
    }

    private boolean actorHasPermission(String tenantId, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return false;
        }
        List<String> profiles = jdbcTemplate.queryForList(
                "SELECT profile_id FROM platform_user WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'",
                String.class, actorId, tenantId);
        if (profiles.isEmpty() || profiles.getFirst() == null) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profiles.getFirst()).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }

    private String userId(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        Map<String, Object> config = objectMapper.readValue(configJson, new TypeReference<>() {});
        return config.get("userId") instanceof String id && !id.isBlank() ? id : null;
    }

    @Override
    public void validate(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("ANONYMIZE_USER requires a userId parameter");
        }
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, new TypeReference<>() {});
            if (!(config.get("userId") instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("ANONYMIZE_USER requires a userId parameter");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
