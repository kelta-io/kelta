//! The member quota hook (the Java module's `MemberEntitlementQuotaHook`): before a record is
//! created in a collection with active `billing_entitlement_rules`, count the owner's records
//! there and refuse the create when the owner's plan limit is reached.
//!
//! Fail-open throughout, like the Java hook: a rule, entitlement or count the module cannot read
//! allows the write. Counting goes through the platform API, so the principal must be able to read
//! the counted collection — the manifest asks for `VIEW_ALL_DATA` for that reason.
//!
//! Differences from the Java hook, both because the protocol tells a module more:
//! - the owner is the record's `createdBy`, or the acting user when the record carries none (the
//!   platform sends `{}` for a collection the principal cannot read);
//! - rules with `appliesTo = PORTAL` are enforced when the acting user is a portal member. The JAR
//!   could not see the actor tier and skipped them; the assertion's `act.user_type` carries it.

use kelta_module::client::{ClientError, Platform, Query, Resource};
use kelta_module::protocol::{Actor, HookResponse, MemberEntitlements, Record, ValidationError};
use serde_json::{Map, Value};

use crate::store::{filter_value, is_module_collection};
use crate::values::{integer, json_object, text_of};

/// The record field naming its owner.
pub const OWNER_FIELD: &str = "createdBy";

/// Rules with this `appliesTo` hold only for portal members.
pub const APPLIES_TO_PORTAL: &str = "PORTAL";

/// What the quota check needs from the module: rules, entitlements, and a count.
pub trait QuotaSource {
    /// The tenant's active rules.
    fn rules(&self) -> impl Future<Output = Result<std::sync::Arc<Vec<Resource>>, ClientError>> + Send;
    /// `user`'s entitlements.
    fn entitlements(&self, user: &str) -> impl Future<Output = Result<MemberEntitlements, ClientError>> + Send;
}

/// Decides a `beforeCreate` on `collection`.
pub async fn before_create<P: Platform, S: QuotaSource>(
    platform: &P,
    source: &S,
    collection: &str,
    record: Option<&Record>,
    actor: Option<&Actor>,
) -> HookResponse {
    // Never let a quota rule cap the module's own bookkeeping.
    if is_module_collection(collection) {
        return HookResponse::allow();
    }
    let rules = match source.rules().await {
        Ok(rules) => rules,
        Err(e) => {
            tracing::warn!(error = %e, "could not read quota rules; allowing");
            return HookResponse::allow();
        }
    };
    let applicable: Vec<&Resource> = rules
        .iter()
        .filter(|rule| text_of(&rule.attributes, "collectionName").as_deref() == Some(collection))
        .collect();
    if applicable.is_empty() {
        return HookResponse::allow();
    }
    let owner = record
        .and_then(|r| text_of(r, OWNER_FIELD))
        .or_else(|| actor.map(|a| a.sub.clone()).filter(|s| !s.trim().is_empty()));
    let Some(owner) = owner else {
        // No actor (flow, schedule, import): quotas are per member, nothing to enforce.
        return HookResponse::allow();
    };
    let portal_member = actor.is_some_and(Actor::is_portal);

    for rule in applicable {
        let applies_to_portal =
            text_of(&rule.attributes, "appliesTo").is_some_and(|a| a.eq_ignore_ascii_case(APPLIES_TO_PORTAL));
        if applies_to_portal && !portal_member {
            continue;
        }
        if let Some(rejection) = enforce(platform, source, rule, &owner, collection).await {
            return rejection;
        }
    }
    HookResponse::allow()
}

async fn enforce<P: Platform, S: QuotaSource>(
    platform: &P,
    source: &S,
    rule: &Resource,
    owner: &str,
    collection: &str,
) -> Option<HookResponse> {
    let limit_key = text_of(&rule.attributes, "limitKey")?;
    let entitlements = match source.entitlements(owner).await {
        Ok(e) => e,
        Err(e) => {
            tracing::warn!(limit_key, error = %e, "could not resolve entitlements; allowing");
            return None;
        }
    };
    // No such entitlement for this member: uncapped. A hard cap belongs on the DEFAULT plan.
    let raw = entitlements.values.get(&limit_key).filter(|v| !v.is_null())?;
    if let Value::Bool(included) = raw {
        // A boolean gate: "this feature is in the plan".
        return (!included).then(|| reject(rule, 0, 0));
    }
    let Some(limit) = integer(Some(raw)) else {
        tracing::warn!(limit_key, "quota limit is not a number; allowing");
        return None;
    };
    if limit < 0 {
        return None; // unlimited
    }
    if limit == 0 {
        return Some(reject(rule, 0, 0));
    }

    let mut query = Query::new().eq(OWNER_FIELD, owner).page_size(1);
    match rule.attributes.get("countFilter") {
        None | Some(Value::Null) => {}
        raw => match json_object(raw) {
            Some(filter) => query = with_count_filter(query, &filter),
            None => {
                tracing::warn!(limit_key, "quota countFilter is not an object; allowing");
                return None;
            }
        },
    }
    let used = match platform.list(collection, &query).await {
        Ok(page) => match page.total_count {
            Some(count) => count,
            None => {
                tracing::warn!(limit_key, collection, "the platform reported no totalCount; allowing");
                return None;
            }
        },
        Err(e) => {
            // Includes 403: the principal cannot read this collection, so the rule cannot hold.
            tracing::warn!(limit_key, collection, error = %e, "quota count failed; allowing");
            return None;
        }
    };
    let limit = limit as u64;
    (used >= limit).then(|| reject(rule, used, limit))
}

fn with_count_filter(mut query: Query, filter: &Map<String, Value>) -> Query {
    for (field, value) in filter {
        query = match value {
            Value::Null => query.filter(field, "isnull", "true"),
            other => query.eq(field, filter_value(other)),
        };
    }
    query
}

fn reject(rule: &Resource, used: u64, limit: u64) -> HookResponse {
    tracing::info!(limit_key = ?text_of(&rule.attributes, "limitKey"), used, limit, "refusing a create: member at quota");
    let message = text_of(&rule.attributes, "message").unwrap_or_else(|| {
        format!("You have reached your plan limit ({used}/{limit}). Upgrade your plan to add more.")
    });
    HookResponse::reject(vec![ValidationError { field: None, message, code: None }])
}

#[cfg(test)]
mod tests {
    use super::*;
    use kelta_module::testing::MemoryPlatform;
    use serde_json::json;
    use std::sync::Arc;

    struct Fixed {
        rules: Vec<Resource>,
        values: Value,
    }

    impl QuotaSource for Fixed {
        async fn rules(&self) -> Result<Arc<Vec<Resource>>, ClientError> {
            Ok(Arc::new(self.rules.clone()))
        }

        async fn entitlements(&self, _user: &str) -> Result<MemberEntitlements, ClientError> {
            Ok(MemberEntitlements { values: self.values.as_object().unwrap().clone(), ..Default::default() })
        }
    }

    fn rule(attributes: Value) -> Resource {
        let mut base =
            json!({"collectionName": "watches", "limitKey": "maxWatches", "appliesTo": "ALL", "active": true});
        for (k, v) in attributes.as_object().unwrap() {
            base[k] = v.clone();
        }
        Resource { id: "rule-1".into(), attributes: base.as_object().unwrap().clone() }
    }

    fn record(owner: &str) -> Record {
        json!({ "createdBy": owner }).as_object().unwrap().clone()
    }

    fn platform_with_watches(owner: &str, count: usize) -> MemoryPlatform {
        let platform = MemoryPlatform::new();
        for i in 0..count {
            platform.insert("watches", json!({ "createdBy": owner, "kind": if i % 2 == 0 { "sms" } else { "email" } }));
        }
        platform.insert("watches", json!({ "createdBy": "someone-else" }));
        platform
    }

    async fn decide(
        platform: &MemoryPlatform,
        rules: Vec<Resource>,
        values: Value,
        actor: Option<Actor>,
    ) -> HookResponse {
        let source = Fixed { rules, values };
        before_create(platform, &source, "watches", Some(&record("u1")), actor.as_ref()).await
    }

    fn portal(sub: &str) -> Actor {
        Actor { sub: sub.into(), user_type: Some("PORTAL".into()), profile_id: None }
    }

    #[tokio::test]
    async fn refuses_at_the_limit_and_allows_below_it() {
        let platform = platform_with_watches("u1", 3);
        let at = decide(&platform, vec![rule(json!({}))], json!({"maxWatches": 3}), None).await;
        assert!(!at.allowed);
        assert_eq!(at.errors[0].message, "You have reached your plan limit (3/3). Upgrade your plan to add more.");
        assert_eq!(at.errors[0].field, None);
        let below = decide(&platform, vec![rule(json!({}))], json!({"maxWatches": 4}), None).await;
        assert!(below.allowed);
    }

    #[tokio::test]
    async fn the_rule_message_replaces_the_default() {
        let platform = platform_with_watches("u1", 1);
        let refused =
            decide(&platform, vec![rule(json!({"message": "Upgrade!"}))], json!({"maxWatches": 1}), None).await;
        assert_eq!(refused.errors[0].message, "Upgrade!");
    }

    #[tokio::test]
    async fn the_count_filter_narrows_the_count() {
        let platform = platform_with_watches("u1", 3); // two sms, one email
        let sms = rule(json!({"countFilter": {"kind": "sms"}}));
        assert!(!decide(&platform, vec![sms.clone()], json!({"maxWatches": 2}), None).await.allowed);
        let email = rule(json!({"countFilter": "{\"kind\":\"email\"}"}));
        assert!(decide(&platform, vec![email], json!({"maxWatches": 2}), None).await.allowed);
        let unusable = rule(json!({"countFilter": [1]}));
        assert!(decide(&platform, vec![unusable], json!({"maxWatches": 1}), None).await.allowed);
    }

    #[tokio::test]
    async fn limits_follow_the_java_semantics() {
        let platform = platform_with_watches("u1", 5);
        let rules = || vec![rule(json!({}))];
        assert!(decide(&platform, rules(), json!({}), None).await.allowed, "no key: uncapped");
        assert!(decide(&platform, rules(), json!({"maxWatches": null}), None).await.allowed, "null: uncapped");
        assert!(decide(&platform, rules(), json!({"maxWatches": -1}), None).await.allowed, "negative: unlimited");
        assert!(decide(&platform, rules(), json!({"maxWatches": true}), None).await.allowed, "true gate");
        let gate = decide(&platform, rules(), json!({"maxWatches": false}), None).await;
        assert_eq!(gate.errors[0].message, "You have reached your plan limit (0/0). Upgrade your plan to add more.");
        assert!(!decide(&platform, rules(), json!({"maxWatches": 0}), None).await.allowed, "zero");
        assert!(!decide(&platform, rules(), json!({"maxWatches": "5"}), None).await.allowed, "numeric string");
        assert!(decide(&platform, rules(), json!({"maxWatches": "lots"}), None).await.allowed, "unusable");
        assert!(decide(&platform, rules(), json!({"maxWatches": {"n": 1}}), None).await.allowed, "unusable");
    }

    #[tokio::test]
    async fn fails_open_when_the_count_cannot_be_read() {
        let platform = platform_with_watches("u1", 5);
        platform.refuse("watches");
        assert!(decide(&platform, vec![rule(json!({}))], json!({"maxWatches": 1}), None).await.allowed);
    }

    #[tokio::test]
    async fn module_collections_and_ownerless_writes_are_never_capped() {
        let platform = platform_with_watches("u1", 5);
        let source =
            Fixed { rules: vec![rule(json!({"collectionName": "billing_passes"}))], values: json!({"maxWatches": 0}) };
        assert!(before_create(&platform, &source, "billing_passes", Some(&record("u1")), None).await.allowed);
        let source = Fixed { rules: vec![rule(json!({}))], values: json!({"maxWatches": 0}) };
        assert!(before_create(&platform, &source, "watches", Some(&Map::new()), None).await.allowed);
        assert!(before_create(&platform, &source, "other", Some(&record("u1")), None).await.allowed);
    }

    #[tokio::test]
    async fn the_actor_owns_a_record_the_module_cannot_read() {
        let platform = platform_with_watches("u1", 2);
        let source = Fixed { rules: vec![rule(json!({}))], values: json!({"maxWatches": 2}) };
        let actor = portal("u1");
        assert!(!before_create(&platform, &source, "watches", Some(&Map::new()), Some(&actor)).await.allowed);
    }

    #[tokio::test]
    async fn portal_rules_hold_only_for_portal_members() {
        let platform = platform_with_watches("u1", 2);
        let rules = || vec![rule(json!({"appliesTo": "portal"}))];
        let staff = Actor { sub: "u1".into(), user_type: Some("INTERNAL".into()), profile_id: None };
        assert!(decide(&platform, rules(), json!({"maxWatches": 2}), Some(staff)).await.allowed);
        assert!(decide(&platform, rules(), json!({"maxWatches": 2}), None).await.allowed);
        assert!(!decide(&platform, rules(), json!({"maxWatches": 2}), Some(portal("u1"))).await.allowed);
    }
}
