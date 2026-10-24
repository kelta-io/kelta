package io.kelta.gateway.websocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.gateway.auth.DynamicReactiveJwtDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opens and closes real WebSockets against {@link RealtimeWebSocketHandler} on an
 * embedded reactor-netty server (the same handshake path as {@code /ws/realtime}),
 * with Netty leak detection at {@code PARANOID}. A close must neither release the
 * {@code CloseWebSocketFrame} twice ({@code IllegalReferenceCountException}) nor
 * leave it unreleased ({@code LEAK:}).
 */
class RealtimeWebSocketHandlerCloseTest {

    private static final int TOKEN_EXPIRED_CODE = 4001;

    private static ResourceLeakDetector.Level previousLevel;
    private static ListAppender<ILoggingEvent> appender;

    private SubscriptionManager subscriptionManager;
    private DynamicReactiveJwtDecoder jwtDecoder;
    private PresenceService presenceService;
    private DisposableServer server;
    private ReactorNettyWebSocketClient client;

    @BeforeAll
    static void enableParanoidLeakDetection() {
        previousLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
    }

    @AfterAll
    static void restoreLeakDetection() {
        rootLogger().detachAppender(appender);
        ResourceLeakDetector.setLevel(previousLevel);
    }

    @BeforeEach
    void startServer() {
        synchronized (appender) {
            appender.list.clear();
        }
        subscriptionManager = mock(SubscriptionManager.class);
        when(subscriptionManager.registerConnection(any(), anyString())).thenReturn(true);
        jwtDecoder = mock(DynamicReactiveJwtDecoder.class);
        presenceService = mock(PresenceService.class);
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(subscriptionManager, jwtDecoder,
                presenceService, mock(ChatMembershipClient.class));

        HandshakeWebSocketService webSocketService = new HandshakeWebSocketService();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder
                        .webHandler(exchange -> webSocketService.handleRequest(exchange, handler))
                        .build()))
                .bindNow();
        client = new ReactorNettyWebSocketClient();
    }

    @AfterEach
    void stopServer() {
        server.disposeNow();
    }

    @Test
    void clientInitiatedCloseReleasesCloseFrameExactlyOnce() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            when(jwtDecoder.decode("client-close-" + i))
                    .thenReturn(Mono.just(jwt(Instant.now().plus(Duration.ofHours(1)))));
            client.execute(uri("client-close-" + i), session -> session.close(CloseStatus.NORMAL))
                    .block(Duration.ofSeconds(10));
        }
        verify(subscriptionManager, timeout(5000).atLeast(20)).removeSession(any());

        assertNoCloseFrameReferenceCountErrors();
    }

    @Test
    void serverInitiatedTokenExpiredCloseReleasesCloseFrameExactlyOnce() throws InterruptedException {
        List<Integer> closeCodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            when(jwtDecoder.decode("token-expired-" + i))
                    .thenReturn(Mono.just(jwt(Instant.now().plusSeconds(1))));
            AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
            client.execute(uri("token-expired-" + i), session -> {
                        session.closeStatus().subscribe(closeStatus::set);
                        return session.receive().then();
                    })
                    .block(Duration.ofSeconds(10));
            closeCodes.add(closeStatus.get() == null ? null : closeStatus.get().getCode());
        }
        verify(subscriptionManager, timeout(5000).atLeast(5)).removeSession(any());

        assertNoCloseFrameReferenceCountErrors();
        assertThat(closeCodes).containsOnly(TOKEN_EXPIRED_CODE);
    }

    private URI uri(String token) {
        return URI.create("ws://127.0.0.1:" + server.port() + "/ws/realtime?token=" + token);
    }

    private static Jwt jwt(Instant expiresAt) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-1")
                .claim("tenant_id", "tenant-1")
                .issuedAt(Instant.now())
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Leak reports are emitted lazily: the detector only notices a collected,
     * unreleased buffer on a later allocation, so force GC and allocate tracked
     * buffers until the reference queue has drained.
     */
    private void assertNoCloseFrameReferenceCountErrors() throws InterruptedException {
        // Mock invocation records hold the sessions (and with them every closed
        // connection's frames); drop them so an unreleased frame can be collected.
        clearInvocations(subscriptionManager, presenceService);
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(100);
            for (int j = 0; j < 100; j++) {
                ByteBuf pooled = PooledByteBufAllocator.DEFAULT.buffer(16);
                pooled.release();
                ByteBuf unpooled = Unpooled.directBuffer(16);
                unpooled.release();
            }
        }

        List<ILoggingEvent> events;
        synchronized (appender) { // AppenderBase.doAppend appends under this lock
            events = List.copyOf(appender.list);
        }
        List<String> offending = events.stream()
                .map(RealtimeWebSocketHandlerCloseTest::render)
                .filter(text -> text.contains("IllegalReferenceCountException")
                        || (text.contains("LEAK:") && text.contains("WebSocketFrame")))
                .toList();
        assertThat(offending).isEmpty();
    }

    private static String render(ILoggingEvent event) {
        String text = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) {
            text += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
        }
        return text;
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
