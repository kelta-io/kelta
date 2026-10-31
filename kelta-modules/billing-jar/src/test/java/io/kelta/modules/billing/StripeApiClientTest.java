package io.kelta.modules.billing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The form body is this module's contract with Stripe, and getting it wrong fails only in
 * production against a real account — so it is asserted against a stub server rather than mocked.
 */
@DisplayName("StripeApiClient — checkout session form body")
class StripeApiClientTest {

    private HttpServer server;
    private StripeApiClient client;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = "{\"url\":\"https://checkout.stripe.test/x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new StripeApiClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Decodes the form-encoded body into a map so assertions read by key. */
    private Map<String, String> form() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : lastBody.get().split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    @DisplayName("Subscription mode stamps metadata on the SUBSCRIPTION, not only the session")
    void subscriptionModeStampsSubscriptionMetadata() {
        client.createCheckoutSession("sk_test", "t1", "u1", "subscription",
                "price_1", "PRO", null, "https://a.test/ok", "https://a.test/no");

        Map<String, String> form = form();
        // Session metadata does not propagate to the subscription Stripe creates. Without
        // subscription_data[metadata], customer.subscription.* events carry no userId and the
        // webhook can only resolve the member via the stored customer mapping — which is
        // order-dependent and drops the event when it arrives first ("no resolvable member",
        // observed live).
        assertThat(form).containsEntry("subscription_data[metadata][userId]", "u1");
        assertThat(form).containsEntry("subscription_data[metadata][tenantId]", "t1");
        assertThat(form).containsEntry("subscription_data[metadata][planCode]", "PRO");
    }

    @Test
    @DisplayName("Both resolution paths are populated: client_reference_id and session metadata")
    void stampsSessionLevelIdentifiersToo() {
        client.createCheckoutSession("sk_test", "t1", "u1", "subscription",
                "price_1", "PRO", null, "https://a.test/ok", "https://a.test/no");

        Map<String, String> form = form();
        assertThat(form).containsEntry("client_reference_id", "u1");
        assertThat(form).containsEntry("metadata[userId]", "u1");
    }

    @Test
    @DisplayName("Payment mode does not send subscription_data")
    void paymentModeOmitsSubscriptionData() {
        // Stripe rejects subscription_data on a one-time payment session.
        client.createCheckoutSession("sk_test", "t1", "u1", "payment",
                "price_1", "DAYPASS", null, "https://a.test/ok", "https://a.test/no");

        assertThat(form().keySet()).noneMatch(k -> k.startsWith("subscription_data"));
    }

    @Test
    @DisplayName("An existing customer is reused rather than duplicated")
    void reusesExistingCustomer() {
        client.createCheckoutSession("sk_test", "t1", "u1", "subscription",
                "price_1", "PRO", "cus_123", "https://a.test/ok", "https://a.test/no");

        assertThat(form()).containsEntry("customer", "cus_123");
    }
}
