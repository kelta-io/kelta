package io.kelta.worker.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthSessionRevocationClient")
class AuthSessionRevocationClientTest {

    private static final String TOKEN = "internal-token-for-tests";

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " token=" + exchange.getRequestHeaders().getFirst("X-Internal-Token") + " body=" + body);
            if (status.get() == 302) {
                exchange.getResponseHeaders().add("Location", "/elsewhere");
            }
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private AuthSessionRevocationClient client(String token) {
        return new AuthSessionRevocationClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", token);
    }

    @Test
    @DisplayName("posts to kelta-auth's internal endpoint with the internal token")
    void postsWithTheToken() {
        assertThat(client(TOKEN).revokeNow("t-1", "u-1", List.of("a@example.test", "a\"b"))).isTrue();

        assertThat(calls).containsExactly("POST /internal/tenants/t-1/users/u-1/revoke-sessions token=" + TOKEN
                + " body={\"loginNames\":[\"a@example.test\",\"a\\\"b\"]}");
    }

    @Test
    @DisplayName("outside a transaction the call is made at once")
    void immediateWithoutTransaction() {
        client(TOKEN).revokeAfterCommit("t-1", "u-1");

        assertThat(calls).singleElement().satisfies(call -> assertThat(call).contains("body={}"));
    }

    @Test
    @DisplayName("inside a transaction the call waits for the commit, and a rollback makes none")
    void afterCommitOnly() {
        TransactionSynchronizationManager.initSynchronization();
        client(TOKEN).revokeAfterCommit("t-1", "u-1");
        assertThat(calls).isEmpty();

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(calls).hasSize(1);

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        client(TOKEN).revokeAfterCommit("t-1", "u-2");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("an error answer, a redirect or an unreachable host is reported, never thrown")
    void failuresAreTolerated() {
        status.set(403);
        assertThat(client(TOKEN).revokeNow("t-1", "u-1", List.of())).isFalse();

        status.set(302);
        assertThat(client(TOKEN).revokeNow("t-1", "u-1", List.of())).isFalse();
        assertThat(calls).as("the redirect is not followed").hasSize(2)
                .noneMatch(call -> call.contains("/elsewhere"));

        assertThat(new AuthSessionRevocationClient("http://127.0.0.1:1", TOKEN).revokeNow("t-1", "u-1", List.of()))
                .isFalse();
    }

    @Test
    @DisplayName("without an internal token nothing is sent")
    void noTokenNoCall() {
        assertThat(client("").revokeNow("t-1", "u-1", List.of())).isFalse();
        client(null).revokeAfterCommit("t-1", "u-1");

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("a missing tenant or user id makes no call")
    void needsBothIds() {
        client(TOKEN).revokeAfterCommit(null, "u-1");
        client(TOKEN).revokeAfterCommit("t-1", " ");

        assertThat(calls).isEmpty();
    }
}
