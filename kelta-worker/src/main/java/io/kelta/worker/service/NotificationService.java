package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.integration.spi.NotificationStore;
import io.kelta.runtime.query.Pagination;
import io.kelta.worker.repository.NotificationRepository;
import io.kelta.worker.repository.NotificationRepository.Notification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * In-app notifications: the {@link NotificationStore} the {@code SEND_NOTIFICATION} flow step
 * writes through, and the recipient's own view behind {@code /api/me/notifications}.
 *
 * <p>Every read and update takes the recipient explicitly and only ever touches that
 * recipient's rows; callers pass the bound caller, never an id from the request.
 */
@Service
public class NotificationService implements NotificationStore {

    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_LINK_LENGTH = 2048;

    public record Page(List<Notification> notifications, long totalCount, long unreadCount) {
    }

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Override
    public String create(String tenantId, NewNotification notification) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("A notification needs a tenant");
        }
        if (notification.title().length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Notification title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        if (notification.link() != null && notification.link().length() > MAX_LINK_LENGTH) {
            throw new IllegalArgumentException(
                    "Notification link must be at most " + MAX_LINK_LENGTH + " characters");
        }
        return TenantContext.callWithTenant(tenantId, () -> {
            String recipientId = repository.findUserId(tenantId, notification.recipient())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Notification recipient '" + notification.recipient()
                                    + "' is not a user of this tenant"));
            return repository.insert(tenantId, recipientId, notification.title(), notification.body(),
                    notification.link(), notification.level());
        });
    }

    public Page list(String tenantId, String recipientId, Pagination pagination) {
        return list(tenantId, recipientId, pagination, false);
    }

    /**
     * One page of the recipient's notifications; with {@code unreadOnly} only the unread ones, and
     * {@code totalCount} counts that filtered set. {@code unreadCount} is always the recipient's
     * unread total.
     */
    public Page list(String tenantId, String recipientId, Pagination pagination, boolean unreadOnly) {
        long unreadCount = repository.countUnread(tenantId, recipientId);
        if (unreadOnly) {
            return new Page(
                    repository.findUnreadByRecipient(tenantId, recipientId, pagination.pageSize(), pagination.offset()),
                    unreadCount,
                    unreadCount);
        }
        return new Page(
                repository.findByRecipient(tenantId, recipientId, pagination.pageSize(), pagination.offset()),
                repository.countByRecipient(tenantId, recipientId),
                unreadCount);
    }

    /** Marks the recipient's notification read; empty when the recipient owns no such notification. */
    public Optional<Notification> markRead(String tenantId, String recipientId, String id) {
        if (!repository.markRead(tenantId, recipientId, id)) {
            return Optional.empty();
        }
        return repository.findByIdForRecipient(tenantId, recipientId, id);
    }

    public int markAllRead(String tenantId, String recipientId) {
        return repository.markAllRead(tenantId, recipientId);
    }

    public long unreadCount(String tenantId, String recipientId) {
        return repository.countUnread(tenantId, recipientId);
    }
}
