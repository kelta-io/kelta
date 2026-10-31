package io.kelta.modules.billing;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Applies verified Stripe webhooks to the module's mirrored billing collections.
 *
 * <p>This is the handler the platform's generic webhook route
 * ({@code POST /api/modules/webhooks/{tenantId}/{moduleId}}) dispatches to. That route is
 * unauthenticated and the platform verifies nothing, so <b>this class is the trust anchor</b>: it
 * resolves the tenant's own {@code stripe} credential and checks the HMAC over the raw body before
 * treating the payload as an event.
 *
 * <p>Failing that check returns {@link ActionResult#failure} rather than throwing, so the platform
 * answers 401 (not a fault worth retrying) instead of 500.
 *
 * <p><b>Idempotency.</b> Redelivery is expected. A pass is granted only once per checkout session
 * and a subscription is upserted by its Stripe id, so replaying an event converges rather than
 * duplicating. This is weaker than the compiled-in implementation's transactional claim row — the
 * module writes through {@code QueryEngine} and has no transaction of its own — and is a known
 * limitation recorded in the module README.
 */
public class ProcessStripeWebhookActionHandler implements ActionHandler {

    public static final String KEY = "billing:stripe-webhook";

    private static final Logger log =
            LoggerFactory.getLogger(ProcessStripeWebhookActionHandler.class);

    /** Credential holding the tenant's Stripe keys. */
    static final String CREDENTIAL_NAME = "stripe";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final BillingCollections collections;
    private final CredentialResolverPort credentialResolver;
    private final StripeSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    public ProcessStripeWebhookActionHandler(BillingCollections collections,
                                             CredentialResolverPort credentialResolver,
                                             StripeSignatureVerifier signatureVerifier,
                                             ObjectMapper objectMapper) {
        this.collections = collections;
        this.credentialResolver = credentialResolver;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getActionTypeKey() {
        return KEY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ActionResult execute(ActionContext context) {
        String tenantId = context.tenantId();
        Map<String, Object> resolved = context.resolvedData() == null
                ? Map.of() : context.resolvedData();
        String rawBody = String.valueOf(resolved.getOrDefault("rawBody", ""));
        Map<String, String> headers =
                (Map<String, String>) resolved.getOrDefault("headers", Map.of());

        String webhookSecret = webhookSecret(tenantId);
        if (webhookSecret == null) {
            // Same answer as a bad signature: distinguishing them would tell an unauthenticated
            // caller which tenants have billing configured.
            return ActionResult.failure("Rejected");
        }
        if (!signatureVerifier.verify(headers.get("stripe-signature"), rawBody, webhookSecret)) {
            return ActionResult.failure("Rejected");
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (RuntimeException e) {
            // Signed but unreadable. Accept it so Stripe stops retrying something we can never
            // process.
            log.warn("Unparseable billing webhook body for tenant {}", tenantId);
            return ActionResult.success();
        }

        String eventType = text(event, "type");
        JsonNode object = event.path("data").path("object");
        if (eventType == null) {
            return ActionResult.success();
        }

        try {
            switch (eventType) {
                case "checkout.session.completed" -> handleCheckoutCompleted(tenantId, object);
                case "customer.subscription.created", "customer.subscription.updated" ->
                        handleSubscriptionUpsert(tenantId, object);
                case "customer.subscription.deleted" -> handleSubscriptionDeleted(tenantId, object);
                default -> log.debug("Ignoring unhandled billing event type {}", eventType);
            }
        } catch (RuntimeException e) {
            // A genuine processing fault: let it out so the platform answers 500 and Stripe
            // retries. Distinct from the rejection path above.
            throw e;
        }
        return ActionResult.success(Map.of("eventType", eventType));
    }

    // ------------------------------------------------------------- Event handlers

    private void handleCheckoutCompleted(String tenantId, JsonNode session) {
        String userId = resolveUserId(tenantId, session);
        if (userId == null) {
            log.warn("Checkout completed for tenant {} with no resolvable member", tenantId);
            return;
        }
        String customerId = text(session, "customer");
        if (customerId != null) {
            collections.upsertCustomer(tenantId, userId, customerId,
                    text(session.path("customer_details"), "email"));
        }

        if (!"payment".equals(text(session, "mode"))) {
            return; // subscription mode: the subscription event does the rest
        }

        String sessionId = text(session, "id");
        if (sessionId == null || collections.passExistsForSession(sessionId)) {
            // The grant is idempotent on the checkout session — a redelivery cannot mint a
            // second pass.
            return;
        }

        String planCode = text(session.path("metadata"), "planCode");
        Optional<Map<String, Object>> plan = planCode == null
                ? Optional.empty() : collections.findPlanByCode(planCode);
        if (plan.isEmpty()) {
            log.warn("Checkout completed for tenant {} with unknown planCode '{}' — no pass granted",
                    tenantId, planCode);
            return;
        }

        Instant startsAt = Instant.now();
        Integer durationDays = asInteger(plan.get().get("passDurationDays"));
        Instant expiresAt = durationDays == null ? null : startsAt.plus(Duration.ofDays(durationDays));

        collections.grantPass(userId, String.valueOf(plan.get().get("id")), sessionId,
                text(session, "payment_intent"), startsAt, expiresAt);
        log.info("Granted billing pass to member {} of tenant {} (plan {}, expires {})",
                userId, tenantId, planCode, expiresAt);
    }

    private void handleSubscriptionUpsert(String tenantId, JsonNode subscription) {
        String subscriptionId = text(subscription, "id");
        String userId = resolveUserId(tenantId, subscription);
        if (subscriptionId == null || userId == null) {
            log.warn("Subscription event for tenant {} with no resolvable member or id", tenantId);
            return;
        }

        String priceId = text(subscription.path("items").path("data").path(0).path("price"), "id");
        String planId = priceId == null ? null
                : collections.findPlanByStripePriceId(priceId)
                        .map(p -> String.valueOf(p.get("id")))
                        .orElse(null);
        if (planId == null) {
            // Mirror it anyway: the status still matters, and the price can be mapped to a plan
            // later without replaying the event.
            log.warn("Subscription {} for tenant {} references unmapped price {}",
                    subscriptionId, tenantId, priceId);
        }

        collections.upsertSubscription(userId, planId, subscriptionId,
                text(subscription, "customer"), text(subscription, "status"),
                currentPeriodEnd(subscription),
                subscription.path("cancel_at_period_end").asBoolean(false),
                epochSeconds(subscription, "canceled_at"));
    }

    private void handleSubscriptionDeleted(String tenantId, JsonNode subscription) {
        String subscriptionId = text(subscription, "id");
        if (subscriptionId == null) {
            return;
        }
        String status = text(subscription, "status");
        collections.markSubscriptionCanceled(subscriptionId,
                status == null ? "canceled" : status,
                epochSeconds(subscription, "canceled_at"));
        log.info("Subscription {} canceled for tenant {}", subscriptionId, tenantId);
    }

    // ------------------------------------------------------------- Helpers

    private String webhookSecret(String tenantId) {
        if (credentialResolver == null || tenantId == null || tenantId.isBlank()) {
            return null;
        }
        try {
            ResolvedCredential credential = credentialResolver.resolve(
                    tenantId, CREDENTIAL_NAME, "STRIPE_WEBHOOK_VERIFY");
            Object secret = credential.secret("webhookSecret");
            if (secret == null || secret.toString().isBlank()) {
                return null;
            }
            return secret.toString();
        } catch (RuntimeException e) {
            // Never echo the reason — it would distinguish "no such tenant" from "credential
            // disabled" to an unauthenticated caller.
            log.debug("Could not resolve Stripe credential for tenant {}", tenantId);
            return null;
        }
    }

    /**
     * Resolves the platform member behind an event: the checkout's {@code client_reference_id},
     * else {@code metadata.userId} (the module stamps both), else the stored customer mapping.
     *
     * <p>Only a platform user UUID counts. Checkouts started before module routes received the
     * resolved UUID stamped the caller's email, and Stripe replays that metadata on every renewal
     * and cancellation forever; trusting it would re-key the member's rows by email, where
     * {@link EntitlementResolver} never looks. Such a value is treated as absent so the customer
     * mapping decides, and a mapping that is itself not a UUID resolves no one.
     */
    private String resolveUserId(String tenantId, JsonNode object) {
        String userId = text(object, "client_reference_id");
        if (isUuid(userId)) {
            return userId;
        }
        userId = text(object.path("metadata"), "userId");
        if (isUuid(userId)) {
            return userId;
        }
        String customerId = text(object, "customer");
        String mapped = customerId == null ? null
                : collections.findCustomerByStripeId(customerId)
                        .map(c -> String.valueOf(c.get("userId")))
                        .orElse(null);
        if (isUuid(mapped)) {
            return mapped;
        }
        if (mapped != null) {
            log.warn("Stripe customer {} of tenant {} maps to a non-UUID member id — run the "
                    + "email-to-UUID rewrite in the billing module's VERIFICATION.md",
                    customerId, tenantId);
        }
        return null;
    }

    static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    /**
     * The subscription's period end, from wherever the event renders it.
     *
     * <p>Stripe moved {@code current_period_end} off the subscription and onto each item in
     * 2025-03-31.basil, and webhook payloads render with the <b>account's</b> API version — not
     * the one this client pins for its own calls — so both shapes arrive. Reading only the old one
     * silently leaves the column null, which is what a member's "renews on" line displays.
     */
    static Instant currentPeriodEnd(JsonNode subscription) {
        Instant topLevel = epochSeconds(subscription, "current_period_end");
        if (topLevel != null) {
            return topLevel;
        }
        Instant latest = null;
        for (JsonNode item : subscription.path("items").path("data")) {
            Instant itemEnd = epochSeconds(item, "current_period_end");
            if (itemEnd != null && (latest == null || itemEnd.isAfter(latest))) {
                latest = itemEnd;
            }
        }
        return latest;
    }

    private static Instant epochSeconds(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        long seconds = value.asLong();
        return seconds <= 0 ? null : Instant.ofEpochSecond(seconds);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
