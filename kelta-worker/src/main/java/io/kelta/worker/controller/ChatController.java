package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.interceptor.SelfScopedController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Scoped chat API (telehealth slice 2). {@code /api/chat/**} is a static
 * gateway route carrying only the blanket API_ACCESS check, so ALL
 * authorization is enforced here: participant membership for reads/sends,
 * INTERNAL user-type for queue views/claims, {@code MANAGE_CHAT} for
 * supervisor views and cross-assignment. The generic JSON:API routes for the
 * chat collections carry no object grants (admin VIEW_ALL_DATA/MODIFY_ALL_DATA
 * only) — this controller is the product path.
 *
 * <p>Actor identity comes exclusively from the gateway-stamped
 * {@code X-User-Id} (email → UUID via {@link UserIdResolver}, fail-closed) and
 * {@code X-User-Type} headers; body-supplied identities are ignored.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController implements SelfScopedController {

    private static final String MANAGE_CHAT = "MANAGE_CHAT";

    private final ChatService chatService;
    private final UserIdResolver userIdResolver;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public ChatController(ChatService chatService,
                          UserIdResolver userIdResolver,
                          CerbosPermissionResolver permissionResolver,
                          BootstrapRepository bootstrapRepository) {
        this.chatService = chatService;
        this.userIdResolver = userIdResolver;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    public record StartConversationRequest(String queueId, String subject, String contextRecordId) {}
    public record SendMessageRequest(String body, String kind) {}
    public record AssignRequest(String assigneeId) {}

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest request,
                                                     @RequestBody(required = false) StartConversationRequest body) {
        String tenantId = requireTenant();
        ChatService.ChatActor actor = actor(request, tenantId);
        Map<String, Object> conversation = chatService.startConversation(tenantId, actor,
                body == null ? null : body.queueId(),
                body == null ? null : body.subject(),
                body == null ? null : body.contextRecordId());
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request,
                                                    @RequestParam(defaultValue = "mine") String view,
                                                    @RequestParam(required = false) String queueId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        String tenantId = requireTenant();
        ChatService.ChatActor actor = actor(request, tenantId);

        if ("queue".equals(view) && actor.isPortal()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Queue view is staff-only");
        }
        if ("all".equals(view)) {
            requireManageChat(request);
        }
        List<Map<String, Object>> conversations =
                chatService.listConversations(tenantId, actor, view, queueId, status, page, size);
        return ResponseEntity.ok(Map.of("data", conversations, "view", view));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request, @PathVariable String id) {
        String tenantId = requireTenant();
        return ResponseEntity.ok(chatService.getConversation(tenantId, actor(request, tenantId), id));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<Map<String, Object>> send(HttpServletRequest request, @PathVariable String id,
                                                    @RequestBody SendMessageRequest body) {
        String tenantId = requireTenant();
        Map<String, Object> message = chatService.sendMessage(tenantId, actor(request, tenantId),
                id, body == null ? null : body.body(), body == null ? null : body.kind());
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<Map<String, Object>> messages(HttpServletRequest request, @PathVariable String id,
                                                        @RequestParam(required = false) String after,
                                                        @RequestParam(defaultValue = "50") int size) {
        String tenantId = requireTenant();
        List<Map<String, Object>> messages =
                chatService.getMessages(tenantId, actor(request, tenantId), id, after, size);
        return ResponseEntity.ok(Map.of("data", messages));
    }

    @PostMapping("/conversations/{id}/assign")
    public ResponseEntity<Map<String, Object>> assign(HttpServletRequest request, @PathVariable String id,
                                                      @RequestBody(required = false) AssignRequest body) {
        String tenantId = requireTenant();
        boolean canManage = hasManageChat(request);
        Map<String, Object> updated = chatService.assign(tenantId, actor(request, tenantId), id,
                body == null ? null : body.assigneeId(), canManage);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/conversations/{id}/close")
    public ResponseEntity<Map<String, Object>> close(HttpServletRequest request, @PathVariable String id) {
        String tenantId = requireTenant();
        return ResponseEntity.ok(chatService.close(tenantId, actor(request, tenantId), id,
                hasManageChat(request)));
    }

    @PostMapping("/conversations/{id}/read-receipt")
    public ResponseEntity<Void> readReceipt(HttpServletRequest request, @PathVariable String id) {
        String tenantId = requireTenant();
        chatService.markRead(tenantId, actor(request, tenantId), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- Helpers

    /** Resolves the acting user from gateway-stamped headers, fail-closed. */
    private ChatService.ChatActor actor(HttpServletRequest request, String tenantId) {
        String identifier = request.getHeader("X-User-Id");
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        String userId = userIdResolver.resolve(identifier, tenantId);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unresolvable identity");
        }
        String userType = request.getHeader("X-User-Type");
        return new ChatService.ChatActor(userId, identifier,
                userType == null || userType.isBlank() ? "INTERNAL" : userType);
    }

    private void requireManageChat(HttpServletRequest request) {
        if (!hasManageChat(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MANAGE_CHAT + " permission required");
        }
    }

    private boolean hasManageChat(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> MANAGE_CHAT.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }
}
