package io.kelta.modules.billing;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Opens Stripe's own billing portal for the calling member and returns the URL.
 *
 * <p>Requires an existing customer: a member who has never transacted has nothing to manage. The
 * caller never names whose account to open — the member comes from the flow's actor — so there is
 * no id to tamper with.
 *
 * <p>The return URL is validated against the tenant's registered origins for the same reason
 * checkout's are: without it a caller could send a member from the billing portal to a page they
 * control.
 */
public class CreatePortalSessionActionHandler implements ActionHandler {

    public static final String KEY = "billing:create-portal-session";

    private static final Logger log =
            LoggerFactory.getLogger(CreatePortalSessionActionHandler.class);

    static final String CREDENTIAL_NAME = "stripe";

    private final BillingCollections collections;
    private final CredentialResolverPort credentialResolver;
    private final StripeApiClient stripeApiClient;

    public CreatePortalSessionActionHandler(BillingCollections collections,
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
        Map<String, Object> input = ActionInputs.of(context);

        if (userId == null || userId.isBlank()) {
            return ActionResult.failure("No calling member");
        }
        String returnUrl = ActionInputs.string(input, "returnUrl");
        if (returnUrl == null) {
            return ActionResult.failure("returnUrl is required");
        }

        String customerId = collections.findCustomerByUserId(userId)
                .map(c -> String.valueOf(c.get("stripeCustomerId")))
                .orElse(null);
        if (customerId == null || customerId.isBlank()) {
            return ActionResult.failure("No billing account for this member");
        }

        ResolvedCredential credential;
        try {
            credential = credentialResolver.resolve(tenantId, CREDENTIAL_NAME, "STRIPE_API:portal");
        } catch (RuntimeException e) {
            log.warn("Tenant {} has no usable Stripe credential: {}", tenantId, e.getMessage());
            return ActionResult.failure("Billing is not configured for this tenant");
        }

        if (!CreateCheckoutSessionActionHandler.isAllowedUrl(returnUrl,
                CreateCheckoutSessionActionHandler.allowedReturnOrigins(credential))) {
            log.warn("Rejected returnUrl outside tenant {}'s allowed origins", tenantId);
            return ActionResult.failure("Return URL is not allowed");
        }

        Object secretKey = credential.secret("secretKey");
        if (secretKey == null || secretKey.toString().isBlank()) {
            return ActionResult.failure("Billing is not configured for this tenant");
        }

        try {
            JsonNode session = stripeApiClient.createBillingPortalSession(
                    secretKey.toString(), customerId, returnUrl);
            JsonNode url = session.get("url");
            if (url == null || !url.isTextual()) {
                return ActionResult.failure("Stripe did not return a portal URL");
            }
            return ActionResult.success(Map.of("url", url.stringValue()));
        } catch (StripeApiException e) {
            // Stripe's own message is logged, never returned — it can name account internals.
            log.error("Stripe rejected a billing portal session (HTTP {}, type={}, code={}): {}",
                    e.getStatus(), e.getErrorType(), e.getErrorCode(), e.getMessage());
            return ActionResult.failure(e.isAuthFailure()
                    ? "Billing is not configured correctly for this tenant"
                    : "Could not open the billing portal");
        }
    }
}
