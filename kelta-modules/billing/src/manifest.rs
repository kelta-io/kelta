//! The module manifest served at `/.well-known/kelta-module.json`.

use kelta_module::manifest::{
    Action, CollectionSpec, FailurePolicy, FieldSpec, Hook, HookEvent, Manifest, ManifestError, ObjectGrant,
    Permissions, Route,
};
use serde_json::Value;

use crate::store::{CUSTOMERS, ENTITLEMENT_RULES, MODULE_COLLECTIONS, PASSES, PLANS, SUBSCRIPTIONS};

/// The module id: stable forever, the assertion `aud`.
pub const MODULE_ID: &str = "kelta-billing";

/// Where the admin UI bundle is served (unauthenticated; the platform pins its hash).
pub const UI_BUNDLE_PATH: &str = "/ui/kelta-billing.js";

/// The admin UI bundle.
pub const UI_BUNDLE: &[u8] = include_bytes!("../ui/ui-bundle.js");

/// The manifest JSON.
pub fn manifest() -> Result<Value, ManifestError> {
    let mut permissions = Permissions::new().credential("stripe");
    // Explicit grants on the module's own collections, so an install that adopts collections a
    // previous (JAR) install left behind still gets read/create/edit on them.
    for collection in MODULE_COLLECTIONS {
        permissions = permissions.object(ObjectGrant::new(collection).read().create().edit());
    }
    // The quota hook counts a member's records in whatever collection a rule names, so it must be
    // able to read every collection. The Java module read them in-process with no restriction.
    permissions = permissions.system("VIEW_ALL_DATA");

    let mut manifest = Manifest::new(MODULE_ID, "Kelta Billing", env!("CARGO_PKG_VERSION"))
        .description("Stripe-backed portal billing: plans, checkout, subscriptions and one-time passes.")
        .author("Kelta")
        .permissions(permissions)
        .collection(plans())
        .collection(customers())
        .collection(subscriptions())
        .collection(passes())
        .collection(rules())
        .action(
            Action::new("create-checkout-session", "Start Checkout")
                .category("Billing")
                .description("Starts a Stripe hosted checkout for the calling member and returns the redirect URL.")
                .timeout_ms(25_000),
        )
        .action(
            Action::new("create-portal-session", "Open Billing Portal")
                .category("Billing")
                .description("Opens Stripe's billing portal for the calling member and returns the redirect URL.")
                .timeout_ms(25_000),
        )
        .action(
            Action::new("resolve-entitlements", "Resolve Member Entitlements")
                .category("Billing")
                .description("Returns the calling member's plan, subscription status and merged entitlements."),
        )
        .action(
            Action::new("expire-passes", "Expire Due Passes")
                .category("Billing")
                .description("Flips due one-time passes to EXPIRED. Runs hourly on the module's own schedule.")
                .timeout_ms(120_000),
        )
        .action(
            Action::new("list-plans", "List Plans")
                .category("Billing")
                .description("The tenant's purchasable plans, for a pricing page. No processor ids."),
        )
        .action(
            Action::new("me", "My Billing")
                .category("Billing")
                .description("The calling member's plan, subscription, live passes and entitlements."),
        )
        .hook(
            Hook::new("*", [HookEvent::BeforeCreate]).order(-90).timeout_ms(2000).failure_policy(FailurePolicy::Ignore),
        )
        .entitlements_service(0)
        .route(Route::new("plans", ["GET"]))
        .route(Route::new("me", ["GET"]))
        .route(Route::new("checkout-sessions", ["POST"]))
        .route(Route::new("portal-sessions", ["POST"]))
        .schedule("expire-passes", "0 0 * * * *")
        .webhook()
        .ui_bundle(UI_BUNDLE_PATH, UI_BUNDLE);
    // Cache invalidation: anything that changes a member's entitlements or the quota rules.
    for collection in [PLANS, SUBSCRIPTIONS, PASSES, ENTITLEMENT_RULES] {
        manifest = manifest.events(collection, ["created", "updated", "deleted"]);
    }
    manifest.build()
}

fn field(name: &str, display: &str, field_type: &str) -> FieldSpec {
    FieldSpec::new(name, display, field_type)
}

fn plans() -> CollectionSpec {
    CollectionSpec::new(PLANS, "Billing Plans")
        .field(field("code", "Code", "STRING").required())
        .field(field("name", "Name", "STRING").required())
        .field(field("description", "Description", "TEXT"))
        .field(field("kind", "Kind", "STRING").required())
        .field(field("stripeProductId", "Stripe Product Id", "STRING"))
        .field(field("stripePriceId", "Stripe Price Id", "STRING"))
        .field(field("entitlements", "Entitlements", "JSON"))
        .field(field("passDurationDays", "Pass Duration (days)", "INTEGER"))
        .field(field("active", "Active", "BOOLEAN"))
        .field(field("sortOrder", "Sort Order", "INTEGER"))
}

fn customers() -> CollectionSpec {
    CollectionSpec::new(CUSTOMERS, "Billing Customers")
        .field(field("userId", "Member", "STRING").required())
        .field(field("stripeCustomerId", "Stripe Customer Id", "STRING").required())
        .field(field("email", "Email", "STRING"))
}

fn subscriptions() -> CollectionSpec {
    CollectionSpec::new(SUBSCRIPTIONS, "Billing Subscriptions")
        .field(field("userId", "Member", "STRING").required())
        .field(field("planId", "Plan", "STRING"))
        .field(field("stripeSubscriptionId", "Stripe Subscription Id", "STRING").required())
        .field(field("stripeCustomerId", "Stripe Customer Id", "STRING"))
        .field(field("status", "Status", "STRING").required())
        .field(field("currentPeriodEnd", "Renews On", "DATETIME"))
        .field(field("cancelAtPeriodEnd", "Cancels At Period End", "BOOLEAN"))
        .field(field("canceledAt", "Canceled At", "DATETIME"))
}

fn passes() -> CollectionSpec {
    CollectionSpec::new(PASSES, "Billing Passes")
        .field(field("userId", "Member", "STRING").required())
        .field(field("planId", "Plan", "STRING"))
        .field(field("stripeCheckoutSessionId", "Checkout Session Id", "STRING").required())
        .field(field("stripePaymentIntentId", "Payment Intent Id", "STRING"))
        .field(field("status", "Status", "STRING").required())
        .field(field("startsAt", "Starts At", "DATETIME"))
        .field(field("expiresAt", "Expires At", "DATETIME"))
}

fn rules() -> CollectionSpec {
    CollectionSpec::new(ENTITLEMENT_RULES, "Billing Entitlement Rules")
        .field(field("collectionName", "Collection", "STRING").required())
        .field(field("limitKey", "Limit Key", "STRING").required())
        .field(field("countFilter", "Count Filter", "JSON"))
        .field(field("appliesTo", "Applies To", "STRING").required())
        .field(field("message", "Message", "STRING"))
        .field(field("active", "Active", "BOOLEAN"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::actions::ACTION_KEYS;

    #[test]
    fn the_manifest_builds_and_declares_every_action() {
        let manifest = manifest().expect("a valid manifest");
        assert_eq!(manifest["id"], MODULE_ID);
        let keys: Vec<&str> =
            manifest["actions"].as_array().unwrap().iter().map(|a| a["key"].as_str().unwrap()).collect();
        assert_eq!(keys, ACTION_KEYS);
        assert_eq!(manifest["permissions"]["credentials"], serde_json::json!(["stripe"]));
        assert_eq!(manifest["permissions"]["objects"].as_array().unwrap().len(), 5);
        let items = manifest["metadata"]["items"].as_array().unwrap();
        assert_eq!(items.iter().filter(|i| i["type"] == "COLLECTION").count(), 5);
        assert_eq!(items.iter().filter(|i| i["type"] == "FIELD").count(), 10 + 3 + 8 + 7 + 6);
        assert_eq!(manifest["ui"]["bundlePath"], UI_BUNDLE_PATH);
    }
}
