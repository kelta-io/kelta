package io.kelta.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tells kelta-auth to sign a user out everywhere — end every HTTP session and revoke every
 * refresh token — after an administrator reset the user's password, deactivated or removed the
 * account here. Calls kelta-auth's
 * {@code POST /internal/tenants/{tenantId}/users/{userId}/revoke-sessions} with the shared
 * {@code X-Internal-Token} ({@code kelta.internal.token}).
 *
 * <p>Best effort: the worker's change stands either way. The call is made after the surrounding
 * transaction commits (immediately when there is none), is bounded by a connect and a response
 * timeout, never follows a redirect, and a failure is logged at ERROR — never thrown to the
 * admin action. Access tokens already issued run to their expiry (minutes).
 *
 * <p>{@code kelta.auth.internal-url} ({@code KELTA_AUTH_INTERNAL_URL}) is kelta-auth's in-cluster
 * address, not its public issuer URL.
 */
@Service
public class AuthSessionRevocationClient {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionRevocationClient.class);

    static final String TOKEN_HEADER = "X-Internal-Token";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;

    public AuthSessionRevocationClient(@Value("${kelta.auth.internal-url:http://kelta-auth:8081}") String baseUrl,
                                       @Value("${kelta.internal.token:}") String internalToken) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Signs tenant {@code tenantId}'s user {@code userId} out once the current transaction commits. */
    public void revokeAfterCommit(String tenantId, String userId) {
        revokeAfterCommit(tenantId, userId, List.of());
    }

    /**
     * As {@link #revokeAfterCommit(String, String)}; {@code loginNames} (email, username) let
     * kelta-auth find the user's tokens when the user row is gone by the time it is called.
     */
    public void revokeAfterCommit(String tenantId, String userId, List<String> loginNames) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revokeNow(tenantId, userId, loginNames);
                }
            });
        } else {
            revokeNow(tenantId, userId, loginNames);
        }
    }

    /** Makes the call now; {@code true} when kelta-auth confirmed it. Never throws. */
    boolean revokeNow(String tenantId, String userId, List<String> loginNames) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("Could not sign user {} (tenant {}) out of kelta-auth: kelta.internal.token is not configured",
                    userId, tenantId);
            return false;
        }
        try {
            URI uri = URI.create(baseUrl + "/internal/tenants/" + encode(tenantId)
                    + "/users/" + encode(userId) + "/revoke-sessions");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(RESPONSE_TIMEOUT)
                    .header(TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(loginNames)))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.error("kelta-auth answered {} when signing user {} (tenant {}) out; their sessions and refresh "
                        + "tokens may still be valid", response.statusCode(), userId, tenantId);
                return false;
            }
            log.info("Signed user {} (tenant {}) out of kelta-auth", userId, tenantId);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while signing user {} (tenant {}) out of kelta-auth", userId, tenantId);
            return false;
        } catch (IOException | RuntimeException e) {
            log.error("Could not sign user {} (tenant {}) out of kelta-auth: {}; their sessions and refresh tokens "
                    + "may still be valid", userId, tenantId, e.toString());
            return false;
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    /** {@code {"loginNames":[...]}} — names only, as JSON strings. */
    static String body(List<String> loginNames) {
        if (loginNames == null || loginNames.isEmpty()) {
            return "{}";
        }
        return loginNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(AuthSessionRevocationClient::jsonString)
                .collect(Collectors.joining(",", "{\"loginNames\":[", "]}"));
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
