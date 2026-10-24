package io.kelta.runtime.module.integration.spi;

/**
 * Persists in-app notifications for the {@code SEND_NOTIFICATION} handler. The host service
 * supplies the implementation through the module context's extensions; without one every
 * {@code SEND_NOTIFICATION} step fails.
 */
public interface NotificationStore {

    /**
     * Writes one notification for a user of {@code tenantId} and returns its id.
     *
     * @throws IllegalArgumentException when {@code notification.recipient()} is not a user
     *         of the tenant
     */
    String create(String tenantId, NewNotification notification);

    /**
     * @param recipient the recipient's {@code platform_user} id or email
     * @param link      optional in-app or absolute URL the notification points at
     * @param level     {@code INFO}, {@code WARNING} or {@code ERROR}
     */
    record NewNotification(String recipient, String title, String body, String link, String level) {
    }
}
