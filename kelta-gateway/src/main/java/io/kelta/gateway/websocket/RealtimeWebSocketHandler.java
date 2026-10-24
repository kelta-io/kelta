package io.kelta.gateway.websocket;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.kelta.gateway.auth.DynamicReactiveJwtDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * WebSocket handler for realtime record change subscriptions.
 *
 * <p>Authenticates connections via JWT query parameter, processes subscribe/unsubscribe
 * messages, and delivers NATS-bridged events to subscribed sessions.
 *
 * @since 1.0.0
 */
@Component
public class RealtimeWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4001, "Token expired");

    private final SubscriptionManager subscriptionManager;
    private final DynamicReactiveJwtDecoder jwtDecoder;
    private final PresenceService presenceService;
    private final ChatMembershipClient chatMembershipClient;

    public RealtimeWebSocketHandler(SubscriptionManager subscriptionManager,
                                     DynamicReactiveJwtDecoder jwtDecoder,
                                     PresenceService presenceService,
                                     ChatMembershipClient chatMembershipClient) {
        this.subscriptionManager = subscriptionManager;
        this.jwtDecoder = jwtDecoder;
        this.presenceService = presenceService;
        this.chatMembershipClient = chatMembershipClient;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Extract JWT from query parameter
        String token = session.getHandshakeInfo().getUri().getQuery();
        if (token == null || !token.startsWith("token=")) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        String jwtToken = token.substring("token=".length());

        // Validate JWT (use default tenant for WebSocket — tenant from claims)
        return jwtDecoder.decode(jwtToken)
                .flatMap(jwt -> handleAuthenticated(session, jwt))
                .onErrorResume(e -> {
                    log.warn("WebSocket auth failed: {}", e.getMessage());
                    return session.close(CloseStatus.POLICY_VIOLATION);
                });
    }

    private Mono<Void> handleAuthenticated(WebSocketSession session, Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenant_id");
        String userId = jwt.getSubject();
        Instant expiresAt = jwt.getExpiresAt();

        if (tenantId == null || tenantId.isBlank()) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        // Check tenant connection limit
        if (!subscriptionManager.registerConnection(session, tenantId)) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        // Store context in session attributes (email feeds presence display).
        session.getAttributes().put("tenantId", tenantId);
        session.getAttributes().put("userId", userId);
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            session.getAttributes().put("userEmail", email);
        }

        log.info("WebSocket connected: session={} user={} tenant={}", session.getId(), userId, tenantId);
        securityLog.info("security_event=WEBSOCKET_CONNECTED user={} tenant={}", userId, tenantId);

        // Create outbound sink for sending messages
        Sinks.Many<WebSocketMessage> outbound = Sinks.many().multicast().onBackpressureBuffer();
        session.getAttributes().put("outbound", outbound);

        // Schedule token expiration check
        Mono<Void> expirationCheck = Mono.empty();
        if (expiresAt != null) {
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isPositive()) {
                // The close is subscribed on its own, outside the zip below: sending it
                // discards the inbound receiver, which completes `inbound` and makes the
                // zip cancel its siblings. Cancelled mid-write, reactor-netty's close Mono
                // releases the CloseWebSocketFrame its queued write then releases again
                // (IllegalReferenceCountException). Building the frame lazily also stops a
                // frame being allocated per connection and dropped unreleased when the
                // client leaves before the token expires.
                expirationCheck = Mono.delay(ttl)
                        .doOnNext(tick -> session.close(TOKEN_EXPIRED).subscribe())
                        .then(Mono.never());
            }
        }

        // Process inbound messages
        Mono<Void> inbound = session.receive()
                .doOnNext(msg -> handleMessage(session, msg, tenantId))
                .doOnComplete(() -> cleanup(session))
                .doOnError(e -> cleanup(session))
                .then();

        // Send outbound messages
        Mono<Void> outboundMono = session.send(outbound.asFlux().map(m -> m));

        return Mono.zip(inbound, outboundMono, expirationCheck.then(Mono.empty()))
                .then()
                .doFinally(signal -> cleanup(session));
    }

    private void handleMessage(WebSocketSession session, WebSocketMessage msg, String tenantId) {
        if (msg.getType() != WebSocketMessage.Type.TEXT) return;

        try {
            @SuppressWarnings("unchecked")
            Map<String, String> payload = MAPPER.readValue(msg.getPayloadAsText(), Map.class);
            String action = payload.get("action");
            String collection = payload.get("collection");
            String resource = payload.get("resource");
            String conversationId = payload.get("conversationId");

            if (action == null) {
                sendMessage(session, Map.of("action", "error", "message", "Missing action"));
                return;
            }
            // Record subscriptions require a collection; presence actions a resource.
            if (("subscribe".equals(action) || "unsubscribe".equals(action)) && collection == null) {
                sendMessage(session, Map.of("action", "error", "message", "Missing action or collection"));
                return;
            }
            if (action.startsWith("presence.") && (resource == null || resource.isBlank())) {
                sendMessage(session, Map.of("action", "error", "message", "Missing resource"));
                return;
            }
            if (action.startsWith("chat.") && (conversationId == null || conversationId.isBlank())) {
                sendMessage(session, Map.of("action", "error", "message", "Missing conversationId"));
                return;
            }

            switch (action) {
                case "subscribe" -> {
                    // Chat collections are conversation-scoped — the tenant-wide
                    // collection channel is closed for them (use chat.join, which
                    // is membership-checked). Belt to RealtimeBridge's skip.
                    if (collection.startsWith("chat-")) {
                        sendMessage(session, Map.of("action", "error",
                                "message", "Chat collections use chat.join"));
                        return;
                    }
                    if (subscriptionManager.subscribe(session, tenantId, collection)) {
                        sendMessage(session, Map.of("action", "subscribed", "collection", collection));
                        log.debug("Session {} subscribed to {}", session.getId(), collection);
                    } else {
                        sendMessage(session, Map.of("action", "error", "message", "Subscription limit reached"));
                    }
                }
                case "unsubscribe" -> {
                    subscriptionManager.unsubscribe(session, tenantId, collection);
                    sendMessage(session, Map.of("action", "unsubscribed", "collection", collection));
                }
                case "presence.join" -> {
                    if (presenceService.join(session, tenantId, resource)) {
                        log.debug("Session {} joined presence {}", session.getId(), resource);
                    } else {
                        sendMessage(session, Map.of("action", "error", "message", "Presence limit reached"));
                    }
                }
                case "presence.leave" -> presenceService.leave(session, tenantId, resource);
                case "chat.join" -> handleChatJoin(session, tenantId, conversationId);
                case "chat.leave" -> subscriptionManager.leaveConversation(session, tenantId, conversationId);
                default -> sendMessage(session, Map.of("action", "error", "message", "Unknown action: " + action));
            }
        } catch (Exception e) {
            log.warn("Failed to parse WebSocket message: {}", e.getMessage());
            sendMessage(session, Map.of("action", "error", "message", "Invalid message format"));
        }
    }

    /**
     * Verifies conversation membership against the worker (reactive, fail-closed)
     * before adding the session to the conversation routing set (telehealth
     * slice 2). The membership call must not block the event loop — the result
     * is applied asynchronously and confirmed with {@code chat.joined} /
     * {@code error} messages.
     */
    private void handleChatJoin(WebSocketSession session, String tenantId, String conversationId) {
        // Prefer the email identity (matches platform_user lookup); the auth-code
        // flow's sub claim is an email anyway, direct-login's is the user UUID —
        // the worker matches either.
        String identity = (String) session.getAttributes().getOrDefault("userEmail",
                session.getAttributes().get("userId"));
        chatMembershipClient.isMember(tenantId, conversationId, identity)
                .subscribe(member -> {
                    if (!Boolean.TRUE.equals(member)) {
                        securityLog.warn("security_event=CHAT_JOIN_DENIED user={} tenant={} conversation={}",
                                identity, tenantId, conversationId);
                        sendMessage(session, Map.of("action", "error",
                                "message", "Not a conversation participant",
                                "conversationId", conversationId));
                        return;
                    }
                    if (subscriptionManager.joinConversation(session, tenantId, conversationId)) {
                        sendMessage(session, Map.of("action", "chat.joined",
                                "conversationId", conversationId));
                    } else {
                        sendMessage(session, Map.of("action", "error",
                                "message", "Conversation join limit reached",
                                "conversationId", conversationId));
                    }
                });
    }

    /**
     * Send a JSON message to a session via its outbound sink.
     */
    @SuppressWarnings("unchecked")
    public void sendMessage(WebSocketSession session, Map<String, ?> payload) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            Sinks.Many<WebSocketMessage> outbound =
                    (Sinks.Many<WebSocketMessage>) session.getAttributes().get("outbound");
            if (outbound != null) {
                outbound.tryEmitNext(session.textMessage(json));
            }
        } catch (JacksonException e) {
            log.error("Failed to serialize WebSocket message", e);
        }
    }

    private void cleanup(WebSocketSession session) {
        subscriptionManager.removeSession(session);
        presenceService.removeSession(session);
        String userId = (String) session.getAttributes().get("userId");
        log.info("WebSocket disconnected: session={} user={}", session.getId(), userId);
    }
}
