package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.module.integration.spi.NotificationStore;
import io.kelta.runtime.module.integration.spi.NotificationStore.NewNotification;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Action handler that writes an in-app notification for a user of the tenant, through the
 * {@link NotificationStore} the host service supplies. The recipient reads it at
 * {@code GET /api/me/notifications}.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "userId": "target-user-id",
 *   "title": "Order Approved",
 *   "message": "Your order has been approved.",
 *   "link": "/app/orders/123",
 *   "level": "INFO"
 * }
 * </pre>
 *
 * <p>If {@code userId} is not specified, the notification goes to the user who triggered the
 * workflow. {@code userId} may be a user id or email; a recipient who is not a user of the
 * tenant fails the step. {@code link} is optional. Level defaults to "INFO" (INFO, WARNING,
 * ERROR). Without a {@link NotificationStore} every execution fails.
 *
 * @since 1.0.0
 */
public class SendNotificationActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SendNotificationActionHandler.class);

    static final Set<String> LEVELS = Set.of("INFO", "WARNING", "ERROR");

    private final ObjectMapper objectMapper;
    private final NotificationStore notificationStore;

    public SendNotificationActionHandler(ObjectMapper objectMapper, NotificationStore notificationStore) {
        this.objectMapper = objectMapper;
        this.notificationStore = notificationStore;
    }

    @Override
    public String getActionTypeKey() {
        return "SEND_NOTIFICATION";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        if (notificationStore == null) {
            return ActionResult.failure("In-app notifications are not available: no NotificationStore is configured");
        }
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String title = (String) config.get("title");
            if (title == null || title.isBlank()) {
                return ActionResult.failure("Notification title is required");
            }

            String message = (String) config.get("message");
            if (message == null || message.isBlank()) {
                return ActionResult.failure("Notification message is required");
            }

            String userId = (String) config.get("userId");
            if (userId == null || userId.isBlank()) {
                userId = context.userId();
            }
            if (userId == null || userId.isBlank()) {
                return ActionResult.failure("Notification recipient is required: set 'userId' "
                    + "or run the flow as a user");
            }

            String level = ((String) config.getOrDefault("level", "INFO")).toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(level)) {
                return ActionResult.failure("Notification level must be one of INFO, WARNING, ERROR");
            }

            String link = (String) config.get("link");
            if (link != null && link.isBlank()) {
                link = null;
            }

            String notificationId = notificationStore.create(context.tenantId(),
                new NewNotification(userId, title, message, link, level));

            log.info("Send notification action: notificationId={}, userId={}, level={}",
                notificationId, userId, level);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("notificationId", notificationId);
            output.put("userId", userId);
            output.put("title", title);
            output.put("message", message);
            if (link != null) {
                output.put("link", link);
            }
            output.put("level", level);
            return ActionResult.success(output);
        } catch (Exception e) {
            log.error("Failed to execute send notification action: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("title") == null) {
                throw new IllegalArgumentException("Config must contain 'title'");
            }
            if (config.get("message") == null) {
                throw new IllegalArgumentException("Config must contain 'message'");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
