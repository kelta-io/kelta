package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.jsonapi.PaginationLinks;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.query.Pagination;
import io.kelta.worker.repository.NotificationRepository.Notification;
import io.kelta.worker.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The caller's own in-app notifications (written by the {@code SEND_NOTIFICATION} flow step).
 *
 * <ul>
 *   <li>{@code GET /api/me/notifications} — newest first, {@code page[number]}/{@code page[size]},
 *       with {@code meta.unreadCount}; {@code ?unread=true} returns only unread ones</li>
 *   <li>{@code POST /api/me/notifications/{id}/read} — marks one read</li>
 *   <li>{@code POST /api/me/notifications/read-all} — marks all of the caller's unread ones read</li>
 * </ul>
 *
 * <p>The recipient is {@link CallerContext#current()} only, so there is no way to name another
 * user: someone else's notification id reads as 404 and is left untouched. No bound caller
 * (internal tier, machine identity) is 404 too. {@code /api/me/**} is a static gateway route
 * with only the blanket {@code API_ACCESS} check, and the worker's record/field advices skip
 * it; this controller is the whole authorization, on top of tenant RLS.
 *
 * <p>Lives under {@code /api/me/} rather than {@code /api/notifications} so it can never shadow
 * a tenant collection named {@code notifications}.
 */
@RestController
@RequestMapping("/api/me/notifications")
public class MyNotificationsController {

    static final String TYPE = "notifications";
    private static final String BASE_PATH = "/api/me/notifications";

    private final NotificationService notificationService;

    public MyNotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) Map<String, String> params) {
        String tenantId = requireTenantId();
        String callerId = requireCallerId();
        Pagination pagination = Pagination.fromParams(params == null ? Map.of() : params);

        boolean unreadOnly = params != null && "true".equalsIgnoreCase(params.get("unread"));

        NotificationService.Page page = notificationService.list(tenantId, callerId, pagination, unreadOnly);
        int totalPages = (int) Math.ceil((double) page.totalCount() / pagination.pageSize());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", page.totalCount());
        meta.put("currentPage", pagination.pageNumber());
        meta.put("pageSize", pagination.pageSize());
        meta.put("totalPages", totalPages);
        meta.put("unreadCount", page.unreadCount());

        List<Map<String, Object>> records = page.notifications().stream()
                .map(MyNotificationsController::record)
                .toList();
        Map<String, Object> document = JsonApiResponseBuilder.collection(TYPE, records, meta);
        document.put("links", PaginationLinks.build(BASE_PATH, params,
                pagination.pageNumber(), pagination.pageSize(), totalPages));
        return ResponseEntity.ok(document);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable String id) {
        String tenantId = requireTenantId();
        String callerId = requireCallerId();
        Notification notification = notificationService.markRead(tenantId, callerId, id)
                .orElseThrow(MyNotificationsController::notFound);
        Map<String, Object> attributes = record(notification);
        attributes.remove("id");
        return ResponseEntity.ok(JsonApiResponseBuilder.single(TYPE, notification.id(), attributes,
                Map.of("unreadCount", notificationService.unreadCount(tenantId, callerId))));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead() {
        String tenantId = requireTenantId();
        String callerId = requireCallerId();
        int updated = notificationService.markAllRead(tenantId, callerId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("markedRead", updated);
        meta.put("unreadCount", notificationService.unreadCount(tenantId, callerId));
        return ResponseEntity.ok(Map.of("meta", meta));
    }

    private static Map<String, Object> record(Notification notification) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", notification.id());
        record.put("title", notification.title());
        record.put("body", notification.body());
        record.put("link", notification.link());
        record.put("level", notification.level());
        record.put("readAt", iso(notification.readAt()));
        record.put("createdAt", iso(notification.createdAt()));
        return record;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String requireTenantId() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw notFound();
        }
        return tenantId;
    }

    private static String requireCallerId() {
        return CallerContext.current()
                .map(CallerContext::userId)
                .filter(id -> !id.isBlank())
                .orElseThrow(MyNotificationsController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    }
}
