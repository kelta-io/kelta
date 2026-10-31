//! Data access over the billing collections, through the platform's JSON:API with the install's
//! access token (the Java module's `BillingCollections`).

use std::cmp::Ordering;

use chrono::{DateTime, Utc};
use kelta_module::client::{ClientError, Platform, Query, Resource};
use serde_json::{Map, Value, json};

use crate::values::{format_instant, instant, text_of};

pub const PLANS: &str = "billing_plans";
pub const CUSTOMERS: &str = "billing_customers";
pub const SUBSCRIPTIONS: &str = "billing_subscriptions";
pub const PASSES: &str = "billing_passes";
pub const ENTITLEMENT_RULES: &str = "billing_entitlement_rules";

/// Every collection this module provisions.
pub const MODULE_COLLECTIONS: [&str; 5] = [PLANS, CUSTOMERS, SUBSCRIPTIONS, PASSES, ENTITLEMENT_RULES];

/// Bound on the subscription rows read per member; far above what re-subscribing produces.
pub const MAX_SUBSCRIPTIONS_PER_MEMBER: u32 = 50;

/// The JSON:API page-size cap.
const MAX_PAGE: u32 = 200;

/// Subscription statuses that confer their plan's entitlements.
pub const ENTITLING_STATUSES: [&str; 3] = ["active", "trialing", "past_due"];

/// True when `name` is one of this module's own collections: the quota hook never caps them.
pub fn is_module_collection(name: &str) -> bool {
    MODULE_COLLECTIONS.contains(&name)
}

/// True when a subscription row's status confers entitlements.
pub fn is_entitling(subscription: &Resource) -> bool {
    text_of(&subscription.attributes, "status")
        .is_some_and(|status| ENTITLING_STATUSES.contains(&status.to_lowercase().as_str()))
}

/// The billing collections of one tenant, as seen through `platform`.
pub struct BillingStore<'a, P> {
    platform: &'a P,
}

impl<'a, P: Platform> BillingStore<'a, P> {
    pub fn new(platform: &'a P) -> Self {
        Self { platform }
    }

    // ------------------------------------------------------------- Plans

    /// Active plans in display order (`sortOrder`, then `code`).
    pub async fn active_plans(&self) -> Result<Vec<Resource>, ClientError> {
        self.list(PLANS, Query::new().eq("active", "true").sort("sortOrder,code").page_size(MAX_PAGE)).await
    }

    pub async fn find_plan_by_code(&self, code: &str) -> Result<Option<Resource>, ClientError> {
        self.first(PLANS, Query::new().eq("code", code)).await
    }

    pub async fn find_plan_by_stripe_price_id(&self, price_id: &str) -> Result<Option<Resource>, ClientError> {
        self.first(PLANS, Query::new().eq("stripePriceId", price_id)).await
    }

    pub async fn find_plan_by_id(&self, id: &str) -> Result<Option<Resource>, ClientError> {
        self.platform.get(PLANS, id).await
    }

    // ------------------------------------------------------------- Customers

    pub async fn find_customer_by_user_id(&self, user_id: &str) -> Result<Option<Resource>, ClientError> {
        self.first(CUSTOMERS, Query::new().eq("userId", user_id)).await
    }

    pub async fn find_customer_by_stripe_id(&self, customer_id: &str) -> Result<Option<Resource>, ClientError> {
        self.first(CUSTOMERS, Query::new().eq("stripeCustomerId", customer_id)).await
    }

    /// Records the member-to-customer mapping, tolerating webhook redelivery.
    pub async fn upsert_customer(
        &self,
        user_id: &str,
        stripe_customer_id: &str,
        email: Option<&str>,
    ) -> Result<(), ClientError> {
        let mut data = Map::new();
        data.insert("userId".into(), json!(user_id));
        data.insert("stripeCustomerId".into(), json!(stripe_customer_id));
        if let Some(email) = email {
            data.insert("email".into(), json!(email));
        }
        match self.find_customer_by_user_id(user_id).await? {
            Some(existing) => self.platform.update(CUSTOMERS, &existing.id, data).await.map(drop),
            None => self.platform.create(CUSTOMERS, data).await.map(drop),
        }
    }

    // ------------------------------------------------------------- Subscriptions

    /// The member's current subscription: see [`current_subscription`].
    pub async fn find_subscription_by_user_id(&self, user_id: &str) -> Result<Option<Resource>, ClientError> {
        let rows = self
            .list(SUBSCRIPTIONS, Query::new().eq("userId", user_id).page_size(MAX_SUBSCRIPTIONS_PER_MEMBER))
            .await?;
        Ok(current_subscription(rows))
    }

    /// Mirrors a Stripe subscription, keyed on its id so a redelivered event updates in place.
    #[allow(clippy::too_many_arguments)]
    pub async fn upsert_subscription(
        &self,
        user_id: &str,
        plan_id: Option<&str>,
        stripe_subscription_id: &str,
        stripe_customer_id: Option<&str>,
        status: Option<&str>,
        current_period_end: Option<DateTime<Utc>>,
        cancel_at_period_end: bool,
        canceled_at: Option<DateTime<Utc>>,
    ) -> Result<(), ClientError> {
        let mut data = Map::new();
        data.insert("userId".into(), json!(user_id));
        data.insert("planId".into(), json!(plan_id));
        data.insert("stripeSubscriptionId".into(), json!(stripe_subscription_id));
        data.insert("stripeCustomerId".into(), json!(stripe_customer_id));
        data.insert("status".into(), json!(status));
        data.insert("currentPeriodEnd".into(), json!(current_period_end.map(format_instant)));
        data.insert("cancelAtPeriodEnd".into(), json!(cancel_at_period_end));
        data.insert("canceledAt".into(), json!(canceled_at.map(format_instant)));
        match self.first(SUBSCRIPTIONS, Query::new().eq("stripeSubscriptionId", stripe_subscription_id)).await? {
            Some(existing) => self.platform.update(SUBSCRIPTIONS, &existing.id, data).await.map(drop),
            None => self.platform.create(SUBSCRIPTIONS, data).await.map(drop),
        }
    }

    /// Marks a subscription canceled; returns its member, if the row exists.
    pub async fn mark_subscription_canceled(
        &self,
        stripe_subscription_id: &str,
        status: &str,
        canceled_at: DateTime<Utc>,
    ) -> Result<Option<String>, ClientError> {
        let Some(existing) =
            self.first(SUBSCRIPTIONS, Query::new().eq("stripeSubscriptionId", stripe_subscription_id)).await?
        else {
            return Ok(None);
        };
        let mut data = Map::new();
        data.insert("status".into(), json!(status));
        data.insert("canceledAt".into(), json!(format_instant(canceled_at)));
        self.platform.update(SUBSCRIPTIONS, &existing.id, data).await?;
        Ok(text_of(&existing.attributes, "userId"))
    }

    // ------------------------------------------------------------- Passes

    /// True when a pass was already granted for this checkout session.
    pub async fn pass_exists_for_session(&self, checkout_session_id: &str) -> Result<bool, ClientError> {
        Ok(self.first(PASSES, Query::new().eq("stripeCheckoutSessionId", checkout_session_id)).await?.is_some())
    }

    pub async fn grant_pass(
        &self,
        user_id: &str,
        plan_id: &str,
        checkout_session_id: &str,
        payment_intent_id: Option<&str>,
        starts_at: DateTime<Utc>,
        expires_at: Option<DateTime<Utc>>,
    ) -> Result<(), ClientError> {
        let mut data = Map::new();
        data.insert("userId".into(), json!(user_id));
        data.insert("planId".into(), json!(plan_id));
        data.insert("stripeCheckoutSessionId".into(), json!(checkout_session_id));
        data.insert("stripePaymentIntentId".into(), json!(payment_intent_id));
        data.insert("status".into(), json!("ACTIVE"));
        data.insert("startsAt".into(), json!(format_instant(starts_at)));
        data.insert("expiresAt".into(), json!(expires_at.map(format_instant)));
        self.platform.create(PASSES, data).await.map(drop)
    }

    /// A member's live passes: expiry is judged at read time, not from the stored status. A pass
    /// with no (readable) expiry is perpetual.
    pub async fn live_passes(&self, user_id: &str, now: DateTime<Utc>) -> Result<Vec<Resource>, ClientError> {
        let rows = self.list(PASSES, Query::new().eq("userId", user_id).eq("status", "ACTIVE").page_size(100)).await?;
        Ok(rows
            .into_iter()
            .filter(|pass| instant(pass.attributes.get("expiresAt")).is_none_or(|expiry| expiry > now))
            .collect())
    }

    /// Flips up to `limit` due ACTIVE passes to EXPIRED, oldest expiry first; the number flipped.
    ///
    /// A tidier, not a gate: entitlement resolution already ignores an expired pass. Two replicas
    /// sweeping at once flip the same row to the same terminal value, which is harmless.
    pub async fn expire_due_passes(&self, now: DateTime<Utc>, limit: u32) -> Result<u32, ClientError> {
        let mut expired = 0;
        // Each round re-reads the first page: the rows it flipped have left the ACTIVE filter.
        for _ in 0..limit.div_ceil(MAX_PAGE) + 1 {
            let size = (limit - expired).min(MAX_PAGE);
            if size == 0 {
                break;
            }
            let page = self.list(PASSES, Query::new().eq("status", "ACTIVE").sort("expiresAt").page_size(size)).await?;
            let due: Vec<&Resource> = page
                .iter()
                .take_while(|pass| instant(pass.attributes.get("expiresAt")).is_some_and(|expiry| expiry <= now))
                .collect();
            for pass in &due {
                let mut data = Map::new();
                data.insert("status".into(), json!("EXPIRED"));
                self.platform.update(PASSES, &pass.id, data).await?;
                expired += 1;
            }
            if due.len() < page.len() || page.len() < size as usize {
                break;
            }
        }
        Ok(expired)
    }

    // ------------------------------------------------------------- Entitlement rules

    /// Every active quota rule of the tenant (at most 200). An absent or unreadable rules
    /// collection yields none, so record writes are never blocked by it.
    pub async fn active_rules(&self) -> Result<Vec<Resource>, ClientError> {
        match self.list(ENTITLEMENT_RULES, Query::new().eq("active", "true").page_size(MAX_PAGE)).await {
            Err(e) if e.is_refused() || e.is_not_found() => Ok(Vec::new()),
            other => other,
        }
    }

    // ------------------------------------------------------------- Internals

    async fn list(&self, collection: &str, query: Query) -> Result<Vec<Resource>, ClientError> {
        Ok(self.platform.list(collection, &query).await?.data)
    }

    async fn first(&self, collection: &str, query: Query) -> Result<Option<Resource>, ClientError> {
        Ok(self.list(collection, query.page_size(1)).await?.into_iter().next())
    }
}

/// The newest entitling row (latest `currentPeriodEnd`, then `createdAt`); failing that, the
/// newest row of any status by the same order. Missing timestamps sort oldest.
pub fn current_subscription(rows: Vec<Resource>) -> Option<Resource> {
    fn newest(a: &Resource, b: &Resource) -> Ordering {
        let key =
            |r: &Resource| (instant(r.attributes.get("currentPeriodEnd")), instant(r.attributes.get("createdAt")));
        key(a).cmp(&key(b))
    }
    // `max_by` keeps the last of equal elements, as Java's `Stream.max` does.
    let live = rows.iter().filter(|r| is_entitling(r)).max_by(|a, b| newest(a, b)).cloned();
    live.or_else(|| rows.into_iter().max_by(newest))
}

/// `value` as a JSON:API filter value.
pub fn filter_value(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: &str, attributes: Value) -> Resource {
        Resource { id: id.into(), attributes: attributes.as_object().unwrap().clone() }
    }

    #[test]
    fn the_newest_entitling_subscription_wins() {
        let rows = vec![
            row("old", json!({"status": "active", "currentPeriodEnd": "2026-01-01T00:00:00Z"})),
            row("new", json!({"status": "trialing", "currentPeriodEnd": "2026-03-01T00:00:00Z"})),
            row("canceled", json!({"status": "canceled", "currentPeriodEnd": "2026-09-01T00:00:00Z"})),
        ];
        assert_eq!(current_subscription(rows).unwrap().id, "new");
    }

    #[test]
    fn without_an_entitling_row_the_newest_of_any_status_wins() {
        let rows = vec![
            row("a", json!({"status": "canceled", "currentPeriodEnd": "2026-01-01T00:00:00Z"})),
            row("b", json!({"status": "incomplete", "createdAt": "2026-02-01T00:00:00Z"})),
            row(
                "c",
                json!({"status": "canceled", "currentPeriodEnd": "2026-01-01T00:00:00Z", "createdAt": "2026-01-05T00:00:00Z"}),
            ),
        ];
        assert_eq!(current_subscription(rows).unwrap().id, "c");
        assert!(current_subscription(Vec::new()).is_none());
    }

    #[test]
    fn statuses_are_entitling_case_insensitively() {
        assert!(is_entitling(&row("x", json!({"status": "PAST_DUE"}))));
        assert!(!is_entitling(&row("x", json!({"status": "unpaid"}))));
        assert!(!is_entitling(&row("x", json!({}))));
    }
}
