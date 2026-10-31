package io.kelta.modules.billing;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Starts a Stripe hosted checkout for the calling member and returns the URL to send them to.
 *
 * <p>Invoked as a flow action (`execute_flow`), which is how a module exposes an operation to the
 * end-user app without a controller of its own. The caller never names whose subscription to act
 * on — the member comes from the flow's actor — so there is no id to tamper with.
 *
 * <p>Return URLs are validated against the tenant's registered origins before they reach Stripe.
 * Without that check a caller could redirect a paying member to a page they control.
 */
public class CreateCheckoutSessionActionHandler implements ActionHandler {

    public static final String KEY = "billing:create-checkout-session";

    private static final Logger log =
            LoggerFactory.getLogger(CreateCheckoutSessionActionHandler.class);

    static final String CREDENTIAL_NAME = "stripe";
    static final String KIND_DEFAULT = "DEFAULT";
    static final String KIND_SUBSCRIPTION = "SUBSCRIPTION";

    private final BillingCollections collections;
    private final CredentialResolverPort credentialResolver;
    private final StripeApiClient stripeApiClient;

    public CreateCheckoutSessionActionHandler(BillingCollections collections,
                                              CredentialResolverPort credentialResolver,
                                              StripeApiClient stripeApiClient) {
        this.collections = collections;
        this.credentialResolver = credentialResolver;
        this.stripeApiClient = stripeApiClient;
    }

    @Override
    public String getActionTypeKey() {
        return KEY;
    }

    @Override
    public ActionResult execute(ActionContext context) {
        String tenantId = context.tenantId();
        String userId = context.userId();
        // ActionInputs, not resolvedData directly: the flow engine passes the state envelope.
        Map<String, Object> input = ActionInputs.of(context);

        String planCode = ActionInputs.string(input, "planCode");
        String successUrl = ActionInputs.string(input, "successUrl");
        String cancelUrl = ActionInputs.string(input, "cancelUrl");
        if (userId == null || userId.isBlank()) {
            return ActionResult.failure("No calling member");
        }
        if (planCode == null || successUrl == null || cancelUrl == null) {
            return ActionResult.failure("planCode, successUrl and cancelUrl are required");
        }

        Optional<Map<String, Object>> found = collections.findPlanByCode(planCode);
        if (found.isEmpty() || !Boolean.TRUE.equals(found.get().get("active"))) {
            return ActionResult.failure("Unknown plan");
        }
        Map<String, Object> plan = found.get();

        String kind = String.valueOf(plan.getOrDefault("kind", KIND_SUBSCRIPTION));
        if (KIND_DEFAULT.equals(kind)) {
            // The free baseline is what a member falls back to; it is not for sale.
            return ActionResult.failure("Plan is not purchasable");
        }
        String priceId = ActionInputs.string(plan, "stripePriceId");
        if (priceId == null) {
            log.warn("Plan {} of tenant {} has no Stripe price id — cannot check out",
                    planCode, tenantId);
            return ActionResult.failure("Plan is not available for purchase");
        }

        ResolvedCredential credential;
        try {
            credential = credentialResolver.resolve(tenantId, CREDENTIAL_NAME,
                    "STRIPE_API:checkout");
        } catch (RuntimeException e) {
            log.warn("Tenant {} has no usable Stripe credential: {}", tenantId, e.getMessage());
            return ActionResult.failure("Billing is not configured for this tenant");
        }

        List<String> allowedOrigins = allowedReturnOrigins(credential);
        if (!isAllowedUrl(successUrl, allowedOrigins) || !isAllowedUrl(cancelUrl, allowedOrigins)) {
            // Log the rejected value — attacker-supplied but not secret — so a tenant
            // misconfiguration is diagnosable. Never echo it to the caller.
            log.warn("Rejected return URL outside tenant {}'s allowed origins", tenantId);
            return ActionResult.failure("Return URL is not allowed");
        }

        Object secretKey = credential.secret("secretKey");
        if (secretKey == null || secretKey.toString().isBlank()) {
            return ActionResult.failure("Billing is not configured for this tenant");
        }

        String customerId = collections.findCustomerByUserId(userId)
                .map(c -> String.valueOf(c.get("stripeCustomerId")))
                .orElse(null);
        String mode = KIND_SUBSCRIPTION.equals(kind) ? "subscription" : "payment";

        try {
            JsonNode session = stripeApiClient.createCheckoutSession(
                    secretKey.toString(), tenantId, userId, mode, priceId, planCode,
                    customerId, successUrl, cancelUrl);
            JsonNode url = session.get("url");
            if (url == null || !url.isTextual()) {
                return ActionResult.failure("Stripe did not return a checkout URL");
            }
            return ActionResult.success(Map.of("url", url.stringValue()));
        } catch (StripeApiException e) {
            // Stripe's own message is logged, never returned — it can name account internals.
            log.error("Stripe rejected a checkout session (HTTP {}, type={}, code={}): {}",
                    e.getStatus(), e.getErrorType(), e.getErrorCode(), e.getMessage());
            return ActionResult.failure(e.isAuthFailure()
                    ? "Billing is not configured correctly for this tenant"
                    : "Could not start checkout");
        }
    }

    /**
     * Reads {@code allowedReturnOrigins} from credential metadata, tolerating both a JSON array
     * and a comma-separated string — the value is tenant-authored through the credential UI.
     */
    static List<String> allowedReturnOrigins(ResolvedCredential credential) {
        Object raw = credential.metadata("allowedReturnOrigins");
        List<String> origins = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(o -> {
                if (o != null && !o.toString().isBlank()) {
                    origins.add(o.toString().trim());
                }
            });
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    origins.add(part.trim());
                }
            }
        }
        return origins;
    }

    /**
     * Matches on scheme + host + port, never a string prefix: {@code https://good.example.com} must
     * not authorize {@code https://good.example.com.evil.test}.
     *
     * <p>Fails closed — no configured origins means no allowed URL.
     */
    /**
     * Whether a return URL may be handed to Stripe, matched against the tenant's allowed origins.
     *
     * <p>Mirrors the semantics of the platform's {@code ReturnUrlValidator}, which this replaced:
     * both sides compare a <em>normalised origin</em> rather than raw URI parts, because the raw
     * comparison disagrees with itself -- {@code https://a.test} carries port -1 and
     * {@code https://a.test:443} carries 443, so a correctly configured origin can reject its own
     * URL.
     *
     * <p>Two rules are security rules, not tidiness. Credentials in the authority
     * ({@code https://paypal.com@attacker.test/}) make the real host unreadable to a person
     * glancing at the address bar, so any userinfo rejects the URL outright. And plain HTTP is
     * refused except on loopback: this URL is where a member lands after paying, and downgrading
     * that hop leaks the session to the network. A tenant cannot opt out by configuring an
     * {@code http://} origin.
     */
    static boolean isAllowedUrl(String url, List<String> allowedOrigins) {
        if (url == null || url.isBlank() || allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }
        String origin = originOf(url);
        if (origin == null) {
            return false;
        }
        for (String allowed : allowedOrigins) {
            String allowedOrigin = originOf(allowed);
            if (allowedOrigin != null && allowedOrigin.equals(origin)) {
                return true;
            }
        }
        return false;
    }

    /** The scheme://host[:port] of a URL with default ports dropped, or null if it is not usable. */
    private static String originOf(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return null;
        }
        if (uri.getUserInfo() != null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);

        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            return null;
        }

        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

}
