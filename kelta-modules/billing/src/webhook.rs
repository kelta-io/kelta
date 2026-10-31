//! Inbound Stripe webhooks (the Java module's `ProcessStripeWebhookActionHandler`).
//!
//! The answer goes back to Stripe: `2xx` stops its retries, anything else asks for one.
//! - no usable credential or a bad signature: `401`, the same answer for both, so an
//!   unauthenticated caller cannot tell which tenants have billing configured;
//! - the platform could not hand over the credential right now: `503`, so Stripe retries;
//! - signed but unreadable, or an event type the module ignores: `200`;
//! - a processing fault: `500`, so Stripe retries.

use chrono::{DateTime, Duration, TimeZone, Utc};
use kelta_module::client::{ClientError, Platform};
use serde_json::Value;

use crate::store::BillingStore;
use crate::stripe::verify_signature;
use crate::values::{integer, is_uuid, text_of};

/// The tenant credential holding the Stripe keys.
pub const CREDENTIAL_NAME: &str = "stripe";

/// How a webhook was answered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Processed (or deliberately ignored); `event_type` when the body named one.
    Accepted { event_type: Option<String> },
    /// Not from Stripe, or the tenant has no usable signing secret.
    Rejected,
    /// The credential could not be read right now.
    Unavailable,
    /// Processing failed; Stripe should retry.
    Failed,
}

/// The members whose entitlements a webhook changed.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Touched {
    pub members: Vec<String>,
}

/// Verifies and applies one Stripe event.
pub async fn process<P: Platform>(
    platform: &P,
    body: &[u8],
    signature: Option<&str>,
    now: DateTime<Utc>,
) -> (Outcome, Touched) {
    let secret = match platform.credential(CREDENTIAL_NAME, Some("STRIPE_WEBHOOK_VERIFY")).await {
        Ok(credential) => credential.secret("webhookSecret").map(str::to_string),
        Err(e) if e.is_transient() => {
            tracing::warn!(error = %e, "could not read the Stripe credential; asking Stripe to retry");
            return (Outcome::Unavailable, Touched::default());
        }
        Err(e) => {
            // Never echo the reason to the caller.
            tracing::debug!(error = %e, "no usable Stripe credential for a webhook");
            None
        }
    };
    let Some(secret) = secret else {
        return (Outcome::Rejected, Touched::default());
    };
    if !verify_signature(signature, body, &secret, now.timestamp()) {
        return (Outcome::Rejected, Touched::default());
    }

    let Ok(event) = serde_json::from_slice::<Value>(body) else {
        // Signed but unreadable: accept, so Stripe stops retrying something never processable.
        tracing::warn!("unparseable Stripe webhook body");
        return (Outcome::Accepted { event_type: None }, Touched::default());
    };
    let Some(event_type) = text(&event, "type") else {
        return (Outcome::Accepted { event_type: None }, Touched::default());
    };
    let object = event.pointer("/data/object").cloned().unwrap_or(Value::Null);
    let store = BillingStore::new(platform);
    let mut touched = Touched::default();
    let applied = match event_type.as_str() {
        "checkout.session.completed" => checkout_completed(&store, &object, now, &mut touched).await,
        "customer.subscription.created" | "customer.subscription.updated" => {
            subscription_upsert(&store, &object, &mut touched).await
        }
        "customer.subscription.deleted" => subscription_deleted(&store, &object, now, &mut touched).await,
        other => {
            tracing::debug!(event_type = other, "ignoring an unhandled Stripe event type");
            Ok(())
        }
    };
    match applied {
        Ok(()) => (Outcome::Accepted { event_type: Some(event_type) }, touched),
        Err(e) => {
            tracing::error!(event_type, error = %e, "applying a Stripe event failed; Stripe will retry");
            (Outcome::Failed, touched)
        }
    }
}

async fn checkout_completed<P: Platform>(
    store: &BillingStore<'_, P>,
    session: &Value,
    now: DateTime<Utc>,
    touched: &mut Touched,
) -> Result<(), ClientError> {
    let Some(user_id) = resolve_user_id(store, session).await? else {
        tracing::warn!("checkout completed with no resolvable member");
        return Ok(());
    };
    if let Some(customer_id) = text(session, "customer") {
        let email = session.get("customer_details").and_then(|d| text(d, "email"));
        store.upsert_customer(&user_id, &customer_id, email.as_deref()).await?;
    }
    if text(session, "mode").as_deref() != Some("payment") {
        return Ok(()); // subscription mode: the subscription event does the rest
    }
    let Some(session_id) = text(session, "id") else {
        return Ok(());
    };
    // Idempotent on the checkout session: a redelivery cannot mint a second pass.
    if store.pass_exists_for_session(&session_id).await? {
        return Ok(());
    }
    let plan_code = session.get("metadata").and_then(|m| text(m, "planCode"));
    let plan = match &plan_code {
        Some(code) => store.find_plan_by_code(code).await?,
        None => None,
    };
    let Some(plan) = plan else {
        tracing::warn!(plan_code = ?plan_code, "checkout completed with an unknown plan; no pass granted");
        return Ok(());
    };
    let expires_at = integer(plan.attributes.get("passDurationDays")).map(|days| now + Duration::days(days));
    store
        .grant_pass(&user_id, &plan.id, &session_id, text(session, "payment_intent").as_deref(), now, expires_at)
        .await?;
    tracing::info!(plan_code = ?plan_code, expires_at = ?expires_at, "granted a billing pass");
    touched.members.push(user_id);
    Ok(())
}

async fn subscription_upsert<P: Platform>(
    store: &BillingStore<'_, P>,
    subscription: &Value,
    touched: &mut Touched,
) -> Result<(), ClientError> {
    let subscription_id = text(subscription, "id");
    let user_id = resolve_user_id(store, subscription).await?;
    let (Some(subscription_id), Some(user_id)) = (subscription_id, user_id) else {
        tracing::warn!("subscription event with no resolvable member or id");
        return Ok(());
    };
    let price_id = subscription.pointer("/items/data/0/price").and_then(|p| text(p, "id"));
    let plan_id = match &price_id {
        Some(price) => store.find_plan_by_stripe_price_id(price).await?.map(|p| p.id),
        None => None,
    };
    if plan_id.is_none() {
        // Mirror it anyway: the status still matters, and the price can be mapped later.
        tracing::warn!(price_id = ?price_id, "subscription references an unmapped price");
    }
    store
        .upsert_subscription(
            &user_id,
            plan_id.as_deref(),
            &subscription_id,
            text(subscription, "customer").as_deref(),
            text(subscription, "status").as_deref(),
            current_period_end(subscription),
            subscription.get("cancel_at_period_end").and_then(Value::as_bool).unwrap_or(false),
            epoch_seconds(subscription, "canceled_at"),
        )
        .await?;
    touched.members.push(user_id);
    Ok(())
}

async fn subscription_deleted<P: Platform>(
    store: &BillingStore<'_, P>,
    subscription: &Value,
    now: DateTime<Utc>,
    touched: &mut Touched,
) -> Result<(), ClientError> {
    let Some(subscription_id) = text(subscription, "id") else {
        return Ok(());
    };
    let status = text(subscription, "status").unwrap_or_else(|| "canceled".into());
    let canceled_at = epoch_seconds(subscription, "canceled_at").unwrap_or(now);
    if let Some(member) = store.mark_subscription_canceled(&subscription_id, &status, canceled_at).await? {
        touched.members.push(member);
    }
    Ok(())
}

/// `client_reference_id`, then `metadata.userId`, then the stored customer mapping; only a UUID
/// counts as a member id.
async fn resolve_user_id<P: Platform>(
    store: &BillingStore<'_, P>,
    object: &Value,
) -> Result<Option<String>, ClientError> {
    if let Some(id) = text(object, "client_reference_id").filter(|id| is_uuid(id)) {
        return Ok(Some(id));
    }
    if let Some(id) = object.get("metadata").and_then(|m| text(m, "userId")).filter(|id| is_uuid(id)) {
        return Ok(Some(id));
    }
    let Some(customer_id) = text(object, "customer") else {
        return Ok(None);
    };
    let mapped = store.find_customer_by_stripe_id(&customer_id).await?.and_then(|c| text_of(&c.attributes, "userId"));
    match mapped {
        Some(id) if is_uuid(&id) => Ok(Some(id)),
        Some(_) => {
            tracing::warn!("a Stripe customer maps to a non-UUID member id; see VERIFICATION.md");
            Ok(None)
        }
        None => Ok(None),
    }
}

/// The subscription's `current_period_end`, or the latest of its items' (newer API versions).
pub fn current_period_end(subscription: &Value) -> Option<DateTime<Utc>> {
    epoch_seconds(subscription, "current_period_end").or_else(|| {
        subscription
            .pointer("/items/data")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|item| epoch_seconds(item, "current_period_end"))
            .max()
    })
}

fn epoch_seconds(node: &Value, field: &str) -> Option<DateTime<Utc>> {
    let seconds = node.get(field)?.as_i64()?;
    (seconds > 0).then(|| Utc.timestamp_opt(seconds, 0).single()).flatten()
}

/// A non-blank string field (`text()` in the Java handler): strings only.
fn text(node: &Value, field: &str) -> Option<String> {
    node.get(field).and_then(Value::as_str).filter(|s| !s.trim().is_empty()).map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stripe::sign;
    use kelta_module::protocol::ModuleCredential;
    use kelta_module::testing::MemoryPlatform;
    use serde_json::{Map, json};

    const SECRET: &str = "whsec_test_placeholder";
    const MEMBER: &str = "123e4567-e89b-12d3-a456-426614174000";

    fn platform() -> MemoryPlatform {
        let platform = MemoryPlatform::new();
        let mut secrets = Map::new();
        secrets.insert("webhookSecret".into(), json!(SECRET));
        platform.set_credential(ModuleCredential {
            name: "stripe".into(),
            credential_type: "API_KEY".into(),
            secrets,
            metadata: Map::new(),
        });
        platform.insert(
            "billing_plans",
            json!({"code": "pro", "kind": "SUBSCRIPTION", "stripePriceId": "price_pro", "active": true}),
        );
        platform.insert("billing_plans", json!({"code": "day", "kind": "PASS", "passDurationDays": 1, "active": true}));
        platform
    }

    async fn deliver(platform: &MemoryPlatform, event: Value) -> (Outcome, Touched) {
        let body = event.to_string();
        let now = Utc::now();
        let header = sign(body.as_bytes(), SECRET, now.timestamp());
        process(platform, body.as_bytes(), Some(&header), now).await
    }

    #[tokio::test]
    async fn rejects_bad_signatures_and_missing_credentials_alike() {
        let platform = platform();
        let body = br#"{"type":"checkout.session.completed"}"#;
        assert_eq!(process(&platform, body, Some("t=1,v1=00"), Utc::now()).await.0, Outcome::Rejected);
        let bare = MemoryPlatform::new();
        let header = sign(body, SECRET, Utc::now().timestamp());
        assert_eq!(process(&bare, body, Some(&header), Utc::now()).await.0, Outcome::Rejected);
    }

    #[tokio::test]
    async fn asks_for_a_retry_when_the_credential_is_unavailable() {
        let platform = platform();
        platform.set_unavailable(true);
        let body = b"{}";
        let header = sign(body, SECRET, Utc::now().timestamp());
        assert_eq!(process(&platform, body, Some(&header), Utc::now()).await.0, Outcome::Unavailable);
    }

    #[tokio::test]
    async fn accepts_signed_garbage_and_unknown_types() {
        let platform = platform();
        let body = b"not json";
        let header = sign(body, SECRET, Utc::now().timestamp());
        assert_eq!(process(&platform, body, Some(&header), Utc::now()).await.0, Outcome::Accepted { event_type: None });
        let (outcome, _) = deliver(&platform, json!({"type": "invoice.paid", "data": {"object": {}}})).await;
        assert_eq!(outcome, Outcome::Accepted { event_type: Some("invoice.paid".into()) });
        assert!(platform.writes().is_empty());
    }

    #[tokio::test]
    async fn a_payment_checkout_grants_one_pass_and_maps_the_customer() {
        let platform = platform();
        let event = json!({"type": "checkout.session.completed", "data": {"object": {
            "id": "cs_1", "mode": "payment", "client_reference_id": MEMBER, "customer": "cus_1",
            "customer_details": {"email": "member@example.test"}, "payment_intent": "pi_1",
            "metadata": {"planCode": "day"}}}});
        let (outcome, touched) = deliver(&platform, event.clone()).await;
        assert_eq!(outcome, Outcome::Accepted { event_type: Some("checkout.session.completed".into()) });
        assert_eq!(touched.members, vec![MEMBER.to_string()]);
        let passes = platform.records("billing_passes");
        assert_eq!(passes.len(), 1);
        assert_eq!(passes[0].attributes["status"], "ACTIVE");
        assert_eq!(passes[0].attributes["stripePaymentIntentId"], "pi_1");
        let starts = crate::values::instant(passes[0].attributes.get("startsAt")).unwrap();
        let expires = crate::values::instant(passes[0].attributes.get("expiresAt")).unwrap();
        assert_eq!(expires - starts, Duration::days(1));
        assert_eq!(platform.records("billing_customers")[0].attributes["email"], "member@example.test");
        // Redelivery: no second pass, the customer is updated in place.
        deliver(&platform, event).await;
        assert_eq!(platform.records("billing_passes").len(), 1);
        assert_eq!(platform.records("billing_customers").len(), 1);
    }

    #[tokio::test]
    async fn subscriptions_are_mirrored_by_stripe_id() {
        let platform = platform();
        let sub = |status: &str| {
            json!({"type": "customer.subscription.updated", "data": {"object": {
                "id": "sub_1", "customer": "cus_1", "status": status, "cancel_at_period_end": true,
                "metadata": {"userId": MEMBER},
                "items": {"data": [{"price": {"id": "price_pro"}, "current_period_end": 1_900_000_000},
                                    {"price": {"id": "price_x"}, "current_period_end": 1_900_000_100}]}}}})
        };
        deliver(&platform, sub("active")).await;
        deliver(&platform, sub("past_due")).await;
        let rows = platform.records("billing_subscriptions");
        assert_eq!(rows.len(), 1);
        let row = &rows[0].attributes;
        assert_eq!(row["status"], "past_due");
        assert_eq!(row["planId"], json!(platform.records("billing_plans")[0].id));
        assert_eq!(row["cancelAtPeriodEnd"], true);
        assert_eq!(row["currentPeriodEnd"], "2030-03-17T17:48:20.000Z");
        assert_eq!(row["userId"], MEMBER);
    }

    #[tokio::test]
    async fn the_member_comes_from_the_customer_mapping_last_and_must_be_a_uuid() {
        let platform = platform();
        platform.insert("billing_customers", json!({"userId": MEMBER, "stripeCustomerId": "cus_known"}));
        platform
            .insert("billing_customers", json!({"userId": "legacy@example.test", "stripeCustomerId": "cus_legacy"}));
        let sub = |customer: &str, id: &str| {
            json!({"type": "customer.subscription.created", "data": {"object": {
                "id": id, "customer": customer, "status": "active", "client_reference_id": "not-a-uuid"}}})
        };
        deliver(&platform, sub("cus_known", "sub_a")).await;
        deliver(&platform, sub("cus_legacy", "sub_b")).await;
        let rows = platform.records("billing_subscriptions");
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].attributes["stripeSubscriptionId"], "sub_a");
        assert_eq!(rows[0].attributes["planId"], Value::Null);
    }

    #[tokio::test]
    async fn a_deleted_subscription_is_marked_canceled() {
        let platform = platform();
        platform.insert(
            "billing_subscriptions",
            json!({"userId": MEMBER, "stripeSubscriptionId": "sub_1", "status": "active"}),
        );
        let (_, touched) =
            deliver(&platform, json!({"type": "customer.subscription.deleted", "data": {"object": {"id": "sub_1"}}}))
                .await;
        let row = &platform.records("billing_subscriptions")[0].attributes;
        assert_eq!(row["status"], "canceled");
        assert!(row["canceledAt"].is_string());
        assert_eq!(touched.members, vec![MEMBER.to_string()]);
    }

    #[tokio::test]
    async fn a_store_fault_asks_for_a_retry() {
        let platform = platform();
        platform.refuse("billing_subscriptions");
        let event = json!({"type": "customer.subscription.created", "data": {"object": {
            "id": "sub_1", "status": "active", "metadata": {"userId": MEMBER}}}});
        assert_eq!(deliver(&platform, event).await.0, Outcome::Failed);
    }
}
