package io.kelta.modules.billing;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Return-URL validation is the security-bearing part of checkout: without it a caller could send
 * a paying member to a page they control after payment.
 */
@DisplayName("CreateCheckoutSessionActionHandler")
class CreateCheckoutSessionActionHandlerTest {

    private static final List<String> ALLOWED = List.of("https://app.example.com");

    @Test
    @DisplayName("Accepts a URL on an allowed origin")
    void acceptsAllowedOrigin() {
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.com/billing/done?x=1", ALLOWED)).isTrue();
    }

    @Test
    @DisplayName("Rejects a host that merely starts with an allowed one")
    void rejectsSuffixAttack() {
        // The classic prefix-matching bug: good.example.com must not authorize
        // good.example.com.evil.test.
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.com.evil.test/steal", ALLOWED)).isFalse();
    }

    @Test
    @DisplayName("Rejects a different scheme, host, or port")
    void rejectsOriginMismatch() {
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "http://app.example.com/done", ALLOWED)).isFalse();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://other.example.com/done", ALLOWED)).isFalse();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.com:8443/done", ALLOWED)).isFalse();
    }

    @Test
    @DisplayName("Host comparison is case-insensitive")
    void hostIsCaseInsensitive() {
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://APP.Example.COM/done", ALLOWED)).isTrue();
    }

    @Test
    @DisplayName("Fails closed on empty, blank, or unparseable input")
    void failsClosed() {
        // No configured origins must authorize nothing, rather than everything.
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.com/done", List.of())).isFalse();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(null, ALLOWED)).isFalse();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl("   ", ALLOWED)).isFalse();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "not a url", ALLOWED)).isFalse();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "/relative/path", ALLOWED)).isFalse();
    }

    @Test
    @DisplayName("A malformed configured origin authorizes nothing rather than throwing")
    void malformedConfiguredOriginIsIgnored() {
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.com/done", List.of(":::not-a-url"))).isFalse();
    }

    @Test
    @DisplayName("Credentials in the authority reject the URL, however allowed the host looks")
    void rejectsUserInfo() {
        // https://paypal.com@attacker.test/ reads as PayPal at a glance. The host here IS allowed,
        // so the check cannot lean on host comparison alone -- any userinfo has to reject outright.
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://paypal.com@app.example.test/ok", List.of("https://app.example.test")))
                .isFalse();
    }

    @Test
    @DisplayName("Plain HTTP is refused even when the tenant configured an http:// origin")
    void refusesPlainHttpOffLoopback() {
        // This is where a member lands after paying. A tenant must not be able to downgrade it.
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "http://app.example.test/ok", List.of("http://app.example.test")))
                .isFalse();
    }

    @Test
    @DisplayName("Loopback stays on HTTP so local development still works")
    void allowsHttpOnLoopback() {
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "http://localhost:5173/ok", List.of("http://localhost:5173")))
                .isTrue();
    }

    @Test
    @DisplayName("A default port matches its implicit form, in both directions")
    void normalisesDefaultPorts() {
        // Raw port comparison made a correctly configured origin reject its own URL: URI reports
        // -1 for the implicit form and 443 for the explicit one.
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.test:443/ok", List.of("https://app.example.test")))
                .isTrue();
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.test/ok", List.of("https://app.example.test:443")))
                .isTrue();
    }

    @Test
    @DisplayName("A non-default port still has to match")
    void nonDefaultPortMustMatch() {
        assertThat(CreateCheckoutSessionActionHandler.isAllowedUrl(
                "https://app.example.test:8443/ok", List.of("https://app.example.test")))
                .isFalse();
    }

    /**
     * The webhook and {@link EntitlementResolver} key members by platform user UUID, so the
     * identity the handler stamps on Stripe has to be exactly the {@code context.userId()} the
     * platform hands it. Asserted against a stub Stripe server, as {@code StripeApiClientTest} is.
     */
    @Test
    @DisplayName("Stamps the caller's UUID on client_reference_id and both metadata blocks")
    void stampsCallerUuidOnStripeForm() throws Exception {
        String member = "6f1c2a4e-9b7d-4e1a-8c3f-2d5e7a9b0c1d";
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = "{\"url\":\"https://checkout.stripe.test/x\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            StripeApiClient stripe = new StripeApiClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newHttpClient());
            BillingCollections collections = new BillingCollections(null, null) {
                @Override
                public Optional<Map<String, Object>> findPlanByCode(String code) {
                    return Optional.of(Map.of("id", "plan-pro", "code", code, "active", true,
                            "kind", "SUBSCRIPTION", "stripePriceId", "price_pro"));
                }

                @Override
                public Optional<Map<String, Object>> findCustomerByUserId(String userId) {
                    return Optional.empty();
                }
            };
            CreateCheckoutSessionActionHandler handler = new CreateCheckoutSessionActionHandler(
                    collections,
                    (tenantId, reference, purpose) -> new ResolvedCredential("c1", reference,
                            "API_KEY", Map.of("secretKey", "sk_test"),
                            Map.of("allowedReturnOrigins", "https://app.example.com"),
                            Instant.now()),
                    stripe);

            ActionResult result = handler.execute(ActionContext.builder()
                    .tenantId("t1")
                    .userId(member)
                    .resolvedData(Map.of(
                            "planCode", "PRO",
                            "successUrl", "https://app.example.com/ok",
                            "cancelUrl", "https://app.example.com/no"))
                    .build());

            assertThat(result.successful()).isTrue();
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : lastBody.get().split("&")) {
                int eq = pair.indexOf('=');
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            assertThat(form)
                    .containsEntry("client_reference_id", member)
                    .containsEntry("metadata[userId]", member)
                    .containsEntry("subscription_data[metadata][userId]", member);
        } finally {
            server.stop(0);
        }
    }
}
