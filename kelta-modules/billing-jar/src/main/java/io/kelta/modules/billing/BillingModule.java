package io.kelta.modules.billing;

import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.workflow.module.ModuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Stripe-backed portal billing, as a runtime-installable module.
 *
 * <p>Everything this module needs comes from one uploaded JAR and its {@code kelta-module.json}:
 * the collections it stores state in, the flow actions the app calls, the webhook handler Stripe
 * posts to, and the browser bundle the admin UI renders. Nothing in the platform is recompiled to
 * install it.
 *
 * <p><b>Constructed reflectively</b> by {@code RuntimeModuleManager} via a public no-arg
 * constructor, then given its collaborators in {@link #onStartup(ModuleContext)}. Handlers are
 * therefore built there, not in the constructor — the context does not exist yet at construction.
 */
public class BillingModule implements KeltaModule {

    private static final Logger log = LoggerFactory.getLogger(BillingModule.class);

    private List<ActionHandler> actionHandlers = List.of();
    private List<BeforeSaveHook> beforeSaveHooks = List.of();
    private Map<Class<?>, Object> services = Map.of();

    @Override
    public String getId() {
        return "kelta-billing";
    }

    @Override
    public String getName() {
        return "Kelta Billing";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onStartup(ModuleContext context) {
        CredentialResolverPort credentialResolver =
                context.getExtension(CredentialResolverPort.class);
        if (credentialResolver == null) {
            // Without the vault this module cannot reach Stripe at all, and the alternative —
            // a plaintext key in config — is not one worth offering. Register nothing rather
            // than expose handlers that fail confusingly at call time.
            log.error("Billing module: no CredentialResolverPort available — "
                    + "the credential vault is not configured, so no handlers were registered");
            return;
        }

        BillingCollections collections =
                new BillingCollections(context.queryEngine(), context.collectionRegistry());
        StripeApiClient stripeApiClient = new StripeApiClient(context.objectMapper());
        StripeSignatureVerifier signatureVerifier = new StripeSignatureVerifier();
        EntitlementResolver entitlements = new EntitlementResolver(collections);

        this.actionHandlers = List.of(
                new CreateCheckoutSessionActionHandler(
                        collections, credentialResolver, stripeApiClient),
                new CreatePortalSessionActionHandler(
                        collections, credentialResolver, stripeApiClient),
                new ProcessStripeWebhookActionHandler(
                        collections, credentialResolver, signatureVerifier,
                        context.objectMapper()),
                new ExpirePassesActionHandler(collections),
                new ResolveEntitlementsActionHandler(entitlements),
                new ListPlansActionHandler(collections),
                new MyBillingActionHandler(collections, entitlements));

        // Wildcard hook — runs on every record create for the installing tenant, so its fast path
        // (no rules for the collection) must cost nothing.
        this.beforeSaveHooks = List.of(new MemberEntitlementQuotaHook(
                collections, entitlements, context.collectionRegistry(), context.queryEngine()));

        // Published for platform code to call inline. Without this, entitlement resolution could
        // not leave the worker at all: alert fanout and watch limits need an answer in-process,
        // and handlers and hooks are dispatch-only.
        this.services = Map.of(
                EntitlementProvider.class, new ModuleEntitlementProvider(entitlements));

        log.info("Billing module started with {} action handlers, {} hooks and {} services",
                actionHandlers.size(), beforeSaveHooks.size(), services.size());
    }

    @Override
    public List<ActionHandler> getActionHandlers() {
        return actionHandlers;
    }

    @Override
    public List<BeforeSaveHook> getBeforeSaveHooks() {
        return beforeSaveHooks;
    }

    @Override
    public Map<Class<?>, Object> getServices() {
        return services;
    }
}
