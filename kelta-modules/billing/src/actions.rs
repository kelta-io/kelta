//! The module's actions, shared by flow steps (`kelta-billing:<key>`) and HTTP routes
//! (`/api/modules/kelta-billing/x/<path>`). The calling member is always the verified actor.

use chrono::Utc;
use kelta_module::client::{ClientError, Platform};
use kelta_module::protocol::ModuleCredential;
use serde_json::{Map, Value, json};
use url::Url;

use crate::Tenant;
use crate::quota::QuotaSource;
use crate::stripe::{CheckoutRequest, StripeClient};
use crate::values::{input, text_of};

/// An action's result: an output object, or a failure message safe to show the caller.
pub type ActionResult = Result<Map<String, Value>, String>;

/// The plan kind members fall back to; not for sale.
const KIND_DEFAULT: &str = "DEFAULT";
/// The plan kind sold as a recurring subscription; every other kind is a one-time payment.
const KIND_SUBSCRIPTION: &str = "SUBSCRIPTION";
/// The default number of passes one `expire-passes` run flips.
const DEFAULT_EXPIRY_BATCH: u32 = 500;

/// Every action key, as the manifest declares them.
pub const ACTION_KEYS: [&str; 6] =
    ["create-checkout-session", "create-portal-session", "resolve-entitlements", "expire-passes", "list-plans", "me"];

/// What one action call runs with.
pub struct ActionCall<'a, P> {
    pub tenant: &'a Tenant<'a, P>,
    pub stripe: &'a StripeClient,
    /// The verified acting user, if any.
    pub user_id: Option<&'a str>,
    pub input: &'a Map<String, Value>,
    /// The platform's `Idempotency-Key`, reused for Stripe so a retried step does not repeat.
    pub idempotency_key: Option<&'a str>,
}

/// Runs action `key`; `None` for an unknown key.
pub async fn run<P: Platform>(key: &str, call: &ActionCall<'_, P>) -> Option<ActionResult> {
    Some(match key {
        "create-checkout-session" => create_checkout_session(call).await,
        "create-portal-session" => create_portal_session(call).await,
        "resolve-entitlements" => resolve_entitlements(call).await,
        "expire-passes" => expire_passes(call).await,
        "list-plans" => list_plans(call).await,
        "me" => me(call).await,
        _ => return None,
    })
}

fn member<'a, P>(call: &ActionCall<'a, P>) -> Result<&'a str, String> {
    call.user_id.filter(|u| !u.trim().is_empty()).ok_or_else(|| "No calling member".to_string())
}

/// A platform read failed: logged, and answered without its detail.
fn platform_failure(what: &str) -> impl FnOnce(ClientError) -> String + '_ {
    move |e| {
        tracing::error!(error = %e, "{what} failed reading billing data");
        format!("Could not {what}")
    }
}

fn idempotency_key(call: &ActionCall<'_, impl Platform>, operation: &str) -> String {
    match call.idempotency_key {
        Some(key) => format!("kelta-billing:{operation}:{key}"),
        None => uuid::Uuid::new_v4().to_string(),
    }
}

async fn stripe_credential<P: Platform>(call: &ActionCall<'_, P>, purpose: &str) -> Result<ModuleCredential, String> {
    call.tenant.platform.credential(crate::webhook::CREDENTIAL_NAME, Some(purpose)).await.map_err(|e| {
        tracing::warn!(error = %e, "the tenant has no usable Stripe credential");
        "Billing is not configured for this tenant".to_string()
    })
}

async fn create_checkout_session<P: Platform>(call: &ActionCall<'_, P>) -> ActionResult {
    let user_id = member(call)?;
    let (Some(plan_code), Some(success_url), Some(cancel_url)) =
        (input(call.input, "planCode"), input(call.input, "successUrl"), input(call.input, "cancelUrl"))
    else {
        return Err("planCode, successUrl and cancelUrl are required".into());
    };
    let store = call.tenant.store();
    let fail = platform_failure("start checkout");
    let plan = match store.find_plan_by_code(&plan_code).await {
        Ok(plan) => plan,
        Err(e) => return Err(fail(e)),
    };
    let Some(plan) = plan.filter(|p| p.attributes.get("active") == Some(&Value::Bool(true))) else {
        return Err("Unknown plan".into());
    };
    let kind = text_of(&plan.attributes, "kind").unwrap_or_else(|| KIND_SUBSCRIPTION.into());
    if kind == KIND_DEFAULT {
        return Err("Plan is not purchasable".into());
    }
    let Some(price_id) = input(&plan.attributes, "stripePriceId") else {
        tracing::warn!(plan_code, "plan has no Stripe price id; cannot check out");
        return Err("Plan is not available for purchase".into());
    };
    let credential = stripe_credential(call, "STRIPE_API:checkout").await?;
    let origins = credential.metadata_list("allowedReturnOrigins");
    if !is_allowed_url(&success_url, &origins) || !is_allowed_url(&cancel_url, &origins) {
        tracing::warn!("refused a return URL outside the tenant's allowed origins");
        return Err("Return URL is not allowed".into());
    }
    let Some(secret_key) = credential.secret("secretKey") else {
        return Err("Billing is not configured for this tenant".into());
    };
    let customer_id = match store.find_customer_by_user_id(user_id).await {
        Ok(customer) => customer.and_then(|c| text_of(&c.attributes, "stripeCustomerId")),
        Err(e) => return Err(platform_failure("start checkout")(e)),
    };
    let request = CheckoutRequest {
        tenant_id: call.tenant.tenant_id,
        user_id,
        mode: if kind == KIND_SUBSCRIPTION { "subscription" } else { "payment" },
        price_id: &price_id,
        plan_code: &plan_code,
        customer_id: customer_id.as_deref(),
        success_url: &success_url,
        cancel_url: &cancel_url,
    };
    match call.stripe.create_checkout_session(secret_key, &request, &idempotency_key(call, "checkout")).await {
        Ok(session) => match session.get("url").and_then(Value::as_str) {
            Some(url) => Ok(object(json!({ "url": url }))),
            None => Err("Stripe did not return a checkout URL".into()),
        },
        Err(e) => {
            tracing::error!(error = %e, "Stripe rejected a checkout session");
            Err(if e.is_auth_failure() {
                "Billing is not configured correctly for this tenant".into()
            } else {
                "Could not start checkout".into()
            })
        }
    }
}

async fn create_portal_session<P: Platform>(call: &ActionCall<'_, P>) -> ActionResult {
    let user_id = member(call)?;
    let Some(return_url) = input(call.input, "returnUrl") else {
        return Err("returnUrl is required".into());
    };
    let customer_id = match call.tenant.store().find_customer_by_user_id(user_id).await {
        Ok(customer) => customer.and_then(|c| text_of(&c.attributes, "stripeCustomerId")),
        Err(e) => return Err(platform_failure("open the billing portal")(e)),
    };
    let Some(customer_id) = customer_id else {
        return Err("No billing account for this member".into());
    };
    let credential = stripe_credential(call, "STRIPE_API:portal").await?;
    if !is_allowed_url(&return_url, &credential.metadata_list("allowedReturnOrigins")) {
        tracing::warn!("refused a returnUrl outside the tenant's allowed origins");
        return Err("Return URL is not allowed".into());
    }
    let Some(secret_key) = credential.secret("secretKey") else {
        return Err("Billing is not configured for this tenant".into());
    };
    match call
        .stripe
        .create_billing_portal_session(secret_key, &customer_id, &return_url, &idempotency_key(call, "portal"))
        .await
    {
        Ok(session) => match session.get("url").and_then(Value::as_str) {
            Some(url) => Ok(object(json!({ "url": url }))),
            None => Err("Stripe did not return a portal URL".into()),
        },
        Err(e) => {
            tracing::error!(error = %e, "Stripe rejected a billing portal session");
            Err(if e.is_auth_failure() {
                "Billing is not configured correctly for this tenant".into()
            } else {
                "Could not open the billing portal".into()
            })
        }
    }
}

async fn resolve_entitlements<P: Platform>(call: &ActionCall<'_, P>) -> ActionResult {
    let user_id = member(call)?;
    let resolved = call.tenant.entitlements(user_id).await.map_err(platform_failure("resolve entitlements"))?;
    Ok(object(json!({
        "planCode": resolved.plan_code,
        "subscriptionStatus": resolved.subscription_status,
        "entitlements": resolved.values,
    })))
}

async fn expire_passes<P: Platform>(call: &ActionCall<'_, P>) -> ActionResult {
    let limit = positive_int(call.input.get("limit")).unwrap_or(DEFAULT_EXPIRY_BATCH);
    let expired = call.tenant.store().expire_due_passes(Utc::now(), limit).await.map_err(|e| {
        tracing::error!(error = %e, "pass expiry sweep failed");
        "Pass expiry sweep failed".to_string()
    })?;
    if expired > 0 {
        tracing::info!(expired, "expired billing passes");
    }
    // A full batch tells the caller to run again rather than wait for the next schedule.
    Ok(object(json!({ "expired": expired, "batchFull": expired >= limit })))
}

async fn list_plans<P: Platform>(call: &ActionCall<'_, P>) -> ActionResult {
    let plans = call.tenant.store().active_plans().await.map_err(platform_failure("list plans"))?;
    let data: Vec<Value> = plans
        .iter()
        .map(|plan| {
            let field = |k: &str| plan.attributes.get(k).cloned().unwrap_or(Value::Null);
            json!({
                "code": field("code"),
                "name": field("name"),
                "kind": field("kind"),
                "passDurationDays": field("passDurationDays"),
            })
        })
        .collect();
    Ok(object(json!({ "data": data })))
}

async fn me<P: Platform>(call: &ActionCall<'_, P>) -> ActionResult {
    let user_id = member(call)?;
    let fail = || platform_failure("read billing");
    let resolved = call.tenant.entitlements(user_id).await.map_err(fail())?;
    let store = call.tenant.store();

    let plan = match &resolved.plan_code {
        None => Value::Null,
        Some(code) => {
            let row = store.find_plan_by_code(code).await.map_err(fail())?;
            json!({
                "code": code,
                // Falls back to the code, so a plan deleted under a live subscription still renders.
                "name": row.as_ref().and_then(|p| text_of(&p.attributes, "name")).unwrap_or_else(|| code.clone()),
                "kind": row.as_ref().and_then(|p| text_of(&p.attributes, "kind")),
            })
        }
    };
    let subscription = store.find_subscription_by_user_id(user_id).await.map_err(fail())?.map(|s| {
        let field = |k: &str| s.attributes.get(k).cloned().unwrap_or(Value::Null);
        json!({
            "status": field("status"),
            "currentPeriodEnd": field("currentPeriodEnd"),
            "cancelAtPeriodEnd": field("cancelAtPeriodEnd"),
        })
    });
    let mut passes = Vec::new();
    for pass in store.live_passes(user_id, Utc::now()).await.map_err(fail())? {
        let plan_code = match text_of(&pass.attributes, "planId") {
            Some(plan_id) => {
                store.find_plan_by_id(&plan_id).await.map_err(fail())?.and_then(|p| text_of(&p.attributes, "code"))
            }
            None => None,
        };
        passes.push(json!({
            "planCode": plan_code,
            "expiresAt": pass.attributes.get("expiresAt").cloned().unwrap_or(Value::Null),
        }));
    }
    Ok(object(json!({
        "plan": plan,
        "subscription": subscription,
        "passes": passes,
        "entitlements": resolved.values,
    })))
}

fn object(value: Value) -> Map<String, Value> {
    match value {
        Value::Object(map) => map,
        _ => Map::new(),
    }
}

fn positive_int(value: Option<&Value>) -> Option<u32> {
    let parsed = match value? {
        Value::Number(n) => n.as_i64(),
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }?;
    u32::try_from(parsed).ok().filter(|n| *n > 0)
}

/// True when `url`'s origin is one of `allowed`: https (or http on localhost / 127.0.0.1), no
/// userinfo, default ports dropped. An empty allow-list allows nothing.
pub fn is_allowed_url(url: &str, allowed: &[String]) -> bool {
    let Some(origin) = origin_of(url) else {
        return false;
    };
    allowed.iter().filter_map(|a| origin_of(a)).any(|a| a == origin)
}

fn origin_of(raw: &str) -> Option<String> {
    let url = Url::parse(raw.trim()).ok()?;
    if !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    let host = url.host_str()?.to_ascii_lowercase();
    let scheme = url.scheme();
    let loopback = host == "localhost" || host == "127.0.0.1";
    if scheme != "https" && !(scheme == "http" && loopback) {
        return None;
    }
    // `Url::port` is already `None` for the scheme's default port.
    Some(match url.port() {
        Some(port) => format!("{scheme}://{host}:{port}"),
        None => format!("{scheme}://{host}"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn return_urls_must_match_an_allowed_origin() {
        let allowed = vec!["https://app.example.test".to_string(), "http://localhost:5173".to_string()];
        assert!(is_allowed_url("https://app.example.test/billing/done?x=1", &allowed));
        assert!(is_allowed_url("https://APP.example.test:443/done", &allowed));
        assert!(is_allowed_url("http://localhost:5173/done", &allowed));
        assert!(!is_allowed_url("http://app.example.test/done", &allowed), "http off loopback");
        assert!(!is_allowed_url("https://app.example.test.evil.test/done", &allowed));
        assert!(!is_allowed_url("https://user:pw@app.example.test/done", &allowed), "userinfo");
        assert!(!is_allowed_url("https://app.example.test:8443/done", &allowed), "other port");
        assert!(!is_allowed_url("javascript:alert(1)", &allowed));
        assert!(!is_allowed_url("https://app.example.test/done", &[]), "empty allow-list");
    }

    #[test]
    fn positive_ints() {
        assert_eq!(positive_int(Some(&json!(5))), Some(5));
        assert_eq!(positive_int(Some(&json!("7"))), Some(7));
        assert_eq!(positive_int(Some(&json!(0))), None);
        assert_eq!(positive_int(Some(&json!(-3))), None);
        assert_eq!(positive_int(Some(&json!("x"))), None);
        assert_eq!(positive_int(None), None);
    }
}
