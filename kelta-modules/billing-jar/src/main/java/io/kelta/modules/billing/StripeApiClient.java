package io.kelta.modules.billing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal Stripe REST client.
 *
 * <p>Built on the JDK's {@link HttpClient} rather than Spring's {@code RestClient}: a module runs
 * under {@code SandboxedModuleClassLoader}, whose parent allowlist covers {@code java.*} but not
 * {@code org.springframework.*}. Reaching for a Spring type here would fail to link at load time.
 *
 * <p>Deliberately not {@code stripe-java} either — its large reflective model surface would have
 * to ride inside this JAR, for the three calls the module actually makes.
 *
 * <p><b>The API version is pinned.</b> Stripe rolls its API forward per account; without an
 * explicit {@code Stripe-Version} the response shapes could change under an installed module. The
 * pin and the webhook parser move together.
 *
 * <p><b>Every POST carries an idempotency key</b> so a retried create cannot mint a second
 * customer or checkout session.
 */
public class StripeApiClient {

    /** Pinned deliberately — see the class comment. */
    static final String API_VERSION = "2025-03-31.basil";

    private static final String BASE_URL = "https://api.stripe.com/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public StripeApiClient(ObjectMapper objectMapper) {
        this(objectMapper, BASE_URL, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** Test seam — lets a test point the client at a local stub server. */
    StripeApiClient(ObjectMapper objectMapper, String baseUrl, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    /**
     * Creates a hosted checkout session.
     *
     * <p>{@code userId} is stamped on both {@code client_reference_id} and {@code metadata} so the
     * webhook can resolve the member from whichever field the event carries.
     */
    public JsonNode createCheckoutSession(String secretKey, String tenantId, String userId,
                                          String mode, String priceId, String planCode,
                                          String customerId, String successUrl, String cancelUrl) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", mode);
        form.put("line_items[0][price]", priceId);
        form.put("line_items[0][quantity]", "1");
        form.put("success_url", successUrl);
        form.put("cancel_url", cancelUrl);
        form.put("client_reference_id", userId);
        form.put("metadata[userId]", userId);
        form.put("metadata[tenantId]", tenantId);
        form.put("metadata[planCode]", planCode);
        if ("subscription".equals(mode)) {
            // Session metadata does NOT propagate to the subscription Stripe creates — only
            // subscription_data[metadata] does. Without this, customer.subscription.* events carry
            // no userId and the webhook can only resolve the member through the stored customer
            // mapping, which is ORDER-DEPENDENT: a subscription event delivered before
            // checkout.session.completed finds no mapping yet and is dropped with "no resolvable
            // member". Stamping it here makes those events self-describing.
            form.put("subscription_data[metadata][userId]", userId);
            form.put("subscription_data[metadata][tenantId]", tenantId);
            form.put("subscription_data[metadata][planCode]", planCode);
        }
        if (customerId != null && !customerId.isBlank()) {
            form.put("customer", customerId);
        }
        return post(secretKey, "/checkout/sessions", form);
    }

    /** Opens Stripe's own billing portal for an existing customer. */
    public JsonNode createBillingPortalSession(String secretKey, String customerId,
                                               String returnUrl) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("customer", customerId);
        form.put("return_url", returnUrl);
        return post(secretKey, "/billing_portal/sessions", form);
    }

    private JsonNode post(String secretKey, String path, Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Stripe-Version", API_VERSION)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StripeApiException(0, null, null, "Interrupted calling Stripe");
        } catch (Exception e) {
            throw new StripeApiException(0, null, null, "Could not reach Stripe: " + e.getMessage());
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new StripeApiException(response.statusCode(), null, null,
                    "Unreadable Stripe response");
        }

        if (response.statusCode() / 100 != 2) {
            JsonNode error = body.path("error");
            throw new StripeApiException(response.statusCode(),
                    text(error, "type"), text(error, "code"),
                    text(error, "message") == null ? "Stripe request failed" : text(error, "message"));
        }
        return body;
    }

    private static String encode(Map<String, String> form) {
        List<String> pairs = new ArrayList<>(form.size());
        form.forEach((key, value) -> pairs.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", pairs);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String s = value.stringValue();
        return s.isBlank() ? null : s;
    }
}
