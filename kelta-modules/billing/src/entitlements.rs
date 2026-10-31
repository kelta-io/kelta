//! A member's effective entitlements (the Java module's `EntitlementResolver`).
//!
//! The base plan is the member's subscription plan while the subscription is entitling (active,
//! trialing, past_due), otherwise the tenant's active `DEFAULT` plan. Every live pass's plan is
//! merged on top: numbers SUM, booleans OR, arrays union, anything else last-write-wins.

use chrono::{DateTime, Utc};
use kelta_module::client::{ClientError, Platform, Resource};
use kelta_module::protocol::MemberEntitlements;
use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use serde_json::{Map, Number, Value};

use crate::store::{BillingStore, is_entitling};
use crate::values::{json_object, text_of};

/// The plan kind a member falls back to; never for sale.
pub const KIND_DEFAULT: &str = "DEFAULT";

/// Resolves `user_id`'s entitlements. An unknown member, a tenant with no plans, or a lapsed
/// subscription with no DEFAULT plan all resolve to empty values, never an error: a caller's
/// fallback is always the restrictive one.
pub async fn resolve<P: Platform>(
    store: &BillingStore<'_, P>,
    user_id: &str,
    now: DateTime<Utc>,
) -> Result<MemberEntitlements, ClientError> {
    if user_id.trim().is_empty() {
        return Ok(MemberEntitlements::default());
    }
    let subscription = store.find_subscription_by_user_id(user_id).await?;
    let entitling = subscription.as_ref().is_some_and(is_entitling);

    let mut base_plan = None;
    if entitling && let Some(plan_id) = subscription.as_ref().and_then(|s| text_of(&s.attributes, "planId")) {
        base_plan = store.find_plan_by_id(&plan_id).await?;
    }
    if base_plan.is_none() {
        base_plan = default_plan(store).await?;
    }

    let mut merged = base_plan.as_ref().map(entitlements_of).unwrap_or_default();
    for pass in store.live_passes(user_id, now).await? {
        let Some(plan_id) = text_of(&pass.attributes, "planId") else {
            continue;
        };
        if let Some(plan) = store.find_plan_by_id(&plan_id).await? {
            merge_into(&mut merged, entitlements_of(&plan));
        }
    }

    Ok(MemberEntitlements {
        plan_code: base_plan.as_ref().and_then(|p| text_of(&p.attributes, "code")),
        // Only report a status when it is the one that supplied the baseline; a lapsed member is
        // on DEFAULT.
        subscription_status: if entitling {
            subscription.as_ref().and_then(|s| text_of(&s.attributes, "status"))
        } else {
            None
        },
        values: merged,
    })
}

async fn default_plan<P: Platform>(store: &BillingStore<'_, P>) -> Result<Option<Resource>, ClientError> {
    Ok(store
        .active_plans()
        .await?
        .into_iter()
        .find(|p| text_of(&p.attributes, "kind").is_some_and(|k| k.eq_ignore_ascii_case(KIND_DEFAULT))))
}

/// A plan's opaque entitlement map. A malformed value yields an empty map: one bad plan row must
/// not break every entitlement check for the tenant.
pub fn entitlements_of(plan: &Resource) -> Map<String, Value> {
    match plan.attributes.get("entitlements") {
        None | Some(Value::Null) => Map::new(),
        raw => json_object(raw).unwrap_or_else(|| {
            tracing::warn!(plan = %plan.id, "ignoring plan entitlements that are not a JSON object");
            Map::new()
        }),
    }
}

/// Merges `addition` into `base`: numbers SUM, booleans OR, arrays union, else last-write-wins.
pub fn merge_into(base: &mut Map<String, Value>, addition: Map<String, Value>) {
    for (key, incoming) in addition {
        let merged = match (base.remove(&key), incoming) {
            (None, incoming) => incoming,
            (Some(Value::Number(a)), Value::Number(b)) => Value::Number(sum(&a, &b)),
            (Some(Value::Bool(a)), Value::Bool(b)) => Value::Bool(a || b),
            (Some(Value::Array(mut a)), Value::Array(b)) => {
                for item in b {
                    if !a.contains(&item) {
                        a.push(item);
                    }
                }
                Value::Array(a)
            }
            (Some(_), incoming) => incoming,
        };
        base.insert(key, merged);
    }
}

/// Keeps integral sums integral; fractional values add as decimals (Java's `BigDecimal`).
fn sum(a: &Number, b: &Number) -> Number {
    if let (Some(x), Some(y)) = (a.as_i64(), b.as_i64())
        && let Some(total) = x.checked_add(y)
    {
        return Number::from(total);
    }
    let decimal = |n: &Number| n.to_string().parse::<Decimal>().ok();
    let total = match (decimal(a), decimal(b)) {
        (Some(x), Some(y)) => x.checked_add(y).and_then(|t| t.to_f64()),
        _ => None,
    };
    total.or_else(|| Some(a.as_f64()? + b.as_f64()?)).and_then(Number::from_f64).unwrap_or_else(|| b.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn obj(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn merge_sums_ors_unions_and_overwrites() {
        let mut base = obj(json!({"watches": 2, "sms": false, "channels": ["email"], "tier": "free", "ratio": 0.1}));
        merge_into(
            &mut base,
            obj(
                json!({"watches": 3, "sms": true, "channels": ["email", "sms"], "tier": "pro", "ratio": 0.2, "new": 1}),
            ),
        );
        assert_eq!(
            Value::Object(base),
            json!({"watches": 5, "sms": true, "channels": ["email", "sms"], "tier": "pro", "ratio": 0.3, "new": 1})
        );
    }

    #[test]
    fn integral_sums_stay_integral_and_fractions_do_not_drift() {
        let mut base = obj(json!({"n": 1, "f": 1.0}));
        merge_into(&mut base, obj(json!({"n": 2, "f": 1})));
        assert_eq!(base["n"], json!(3));
        assert!(base["n"].is_i64());
        assert_eq!(base["f"], json!(2.0));
    }

    #[test]
    fn a_number_meeting_a_string_is_last_write_wins() {
        let mut base = obj(json!({"k": 1}));
        merge_into(&mut base, obj(json!({"k": "unlimited"})));
        assert_eq!(base["k"], json!("unlimited"));
    }

    #[test]
    fn malformed_plan_entitlements_are_empty() {
        let plan = |v: Value| Resource { id: "p".into(), attributes: obj(json!({ "entitlements": v })) };
        assert!(entitlements_of(&plan(json!("not json"))).is_empty());
        assert!(entitlements_of(&plan(json!([1]))).is_empty());
        assert_eq!(entitlements_of(&plan(json!("{\"a\":1}")))["a"], json!(1));
    }
}
