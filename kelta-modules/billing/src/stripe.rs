//! Stripe: the two API calls the module makes, and webhook signature verification.

use std::time::Duration;

use hmac::{Hmac, KeyInit, Mac};
use serde_json::Value;
use sha2::Sha256;
use subtle::ConstantTimeEq;

/// The Stripe API version every call pins.
pub const API_VERSION: &str = "2025-03-31.basil";

/// The public Stripe API.
pub const DEFAULT_BASE_URL: &str = "https://api.stripe.com/v1";

const TIMEOUT: Duration = Duration::from_secs(20);

/// How far a webhook signature's timestamp may be from now, either way.
pub const SIGNATURE_TOLERANCE_SECS: i64 = 300;

/// A failed Stripe call. `message` is Stripe's own text: log it, never return it to a caller (it
/// can name account internals).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("Stripe answered {status} (type={error_type:?}, code={code:?}): {message}")]
pub struct StripeError {
    /// HTTP status; 0 when Stripe was not reached.
    pub status: u16,
    pub error_type: Option<String>,
    pub code: Option<String>,
    pub message: String,
}

impl StripeError {
    /// The secret key was refused: the tenant's credential is wrong, not the request.
    pub fn is_auth_failure(&self) -> bool {
        self.status == 401 || self.error_type.as_deref() == Some("authentication_error")
    }
}

/// The parameters of a hosted checkout session.
#[derive(Debug, Clone)]
pub struct CheckoutRequest<'a> {
    pub tenant_id: &'a str,
    pub user_id: &'a str,
    /// `subscription` or `payment`.
    pub mode: &'a str,
    pub price_id: &'a str,
    pub plan_code: &'a str,
    pub customer_id: Option<&'a str>,
    pub success_url: &'a str,
    pub cancel_url: &'a str,
}

/// Calls the Stripe API with a tenant's secret key.
#[derive(Clone)]
pub struct StripeClient {
    http: reqwest::Client,
    base_url: String,
}

impl StripeClient {
    /// A client for `base_url` (normally [`DEFAULT_BASE_URL`]).
    pub fn new(base_url: impl Into<String>) -> Self {
        let http = reqwest::Client::builder()
            .timeout(TIMEOUT)
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("the Stripe HTTP client builds");
        Self { http, base_url: base_url.into().trim_end_matches('/').to_string() }
    }

    /// `POST /checkout/sessions`.
    pub async fn create_checkout_session(
        &self,
        secret_key: &str,
        request: &CheckoutRequest<'_>,
        idempotency_key: &str,
    ) -> Result<Value, StripeError> {
        let mut form: Vec<(&str, &str)> = vec![
            ("mode", request.mode),
            ("line_items[0][price]", request.price_id),
            ("line_items[0][quantity]", "1"),
            ("success_url", request.success_url),
            ("cancel_url", request.cancel_url),
            ("client_reference_id", request.user_id),
            ("metadata[userId]", request.user_id),
            ("metadata[tenantId]", request.tenant_id),
            ("metadata[planCode]", request.plan_code),
        ];
        if request.mode == "subscription" {
            // Session metadata does not reach the subscription Stripe creates; only
            // subscription_data[metadata] does. Without it, customer.subscription.* events carry
            // no userId and resolve the member only through the stored customer mapping, which a
            // subscription event delivered before checkout.session.completed does not find yet.
            form.push(("subscription_data[metadata][userId]", request.user_id));
            form.push(("subscription_data[metadata][tenantId]", request.tenant_id));
            form.push(("subscription_data[metadata][planCode]", request.plan_code));
        }
        if let Some(customer) = request.customer_id.filter(|c| !c.trim().is_empty()) {
            form.push(("customer", customer));
        }
        self.post(secret_key, "/checkout/sessions", &form, idempotency_key).await
    }

    /// `POST /billing_portal/sessions`.
    pub async fn create_billing_portal_session(
        &self,
        secret_key: &str,
        customer_id: &str,
        return_url: &str,
        idempotency_key: &str,
    ) -> Result<Value, StripeError> {
        let form = [("customer", customer_id), ("return_url", return_url)];
        self.post(secret_key, "/billing_portal/sessions", &form, idempotency_key).await
    }

    async fn post(
        &self,
        secret_key: &str,
        path: &str,
        form: &[(&str, &str)],
        idempotency_key: &str,
    ) -> Result<Value, StripeError> {
        let body = url::form_urlencoded::Serializer::new(String::new()).extend_pairs(form).finish();
        let unreachable = |message: String| StripeError { status: 0, error_type: None, code: None, message };
        let response = self
            .http
            .post(format!("{}{path}", self.base_url))
            .bearer_auth(secret_key)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Stripe-Version", API_VERSION)
            .header("Idempotency-Key", idempotency_key)
            .body(body)
            .send()
            .await
            .map_err(|e| unreachable(format!("could not reach Stripe: {}", e.without_url())))?;
        let status = response.status().as_u16();
        let bytes = response.bytes().await.map_err(|e| unreachable(format!("could not read Stripe: {e}")))?;
        let parsed: Value = serde_json::from_slice(&bytes).map_err(|_| StripeError {
            status,
            error_type: None,
            code: None,
            message: "unreadable Stripe response".into(),
        })?;
        if !(200..300).contains(&status) {
            let error = parsed.get("error");
            let field = |k: &str| error.and_then(|e| e.get(k)).and_then(Value::as_str).map(str::to_string);
            return Err(StripeError {
                status,
                error_type: field("type"),
                code: field("code"),
                message: field("message").unwrap_or_else(|| "Stripe request failed".into()),
            });
        }
        Ok(parsed)
    }
}

/// Verifies a `Stripe-Signature` header (`t=<unix>,v1=<hex>[,v1=...]`) over the raw body bytes:
/// HMAC-SHA256 of `"{t}.{body}"` with the endpoint's signing secret, any `v1` matching in constant
/// time, and `t` within [`SIGNATURE_TOLERANCE_SECS`] of `now` in either direction. Fails closed.
pub fn verify_signature(header: Option<&str>, body: &[u8], secret: &str, now: i64) -> bool {
    let (Some(header), false) = (header.filter(|h| !h.trim().is_empty()), secret.trim().is_empty()) else {
        return false;
    };
    let Some((timestamp, signatures)) = parse_signature(header) else {
        return false;
    };
    if signatures.is_empty() || (now - timestamp).abs() > SIGNATURE_TOLERANCE_SECS {
        return false;
    }
    let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(secret.as_bytes()) else {
        return false;
    };
    mac.update(timestamp.to_string().as_bytes());
    mac.update(b".");
    mac.update(body);
    let expected = mac.finalize().into_bytes();
    signatures
        .iter()
        .filter_map(|candidate| hex::decode(candidate).ok())
        .any(|provided| bool::from(provided.as_slice().ct_eq(&expected[..])))
}

fn parse_signature(header: &str) -> Option<(i64, Vec<&str>)> {
    let mut timestamp = None;
    let mut signatures = Vec::new();
    for part in header.split(',') {
        let Some((key, value)) = part.split_once('=') else {
            continue;
        };
        let (key, value) = (key.trim(), value.trim());
        if key == "t" {
            timestamp = Some(value.parse::<i64>().ok()?);
        } else if key == "v1" && !value.is_empty() {
            signatures.push(value);
        }
    }
    Some((timestamp?, signatures))
}

/// A `Stripe-Signature` header for `body`, as Stripe computes it (tests and the harness).
pub fn sign(body: &[u8], secret: &str, timestamp: i64) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes()).expect("HMAC takes any key length");
    mac.update(timestamp.to_string().as_bytes());
    mac.update(b".");
    mac.update(body);
    format!("t={timestamp},v1={}", hex::encode(mac.finalize().into_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "whsec_test_placeholder";
    const BODY: &[u8] = br#"{"id":"evt_1","type":"customer.subscription.updated"}"#;
    const NOW: i64 = 1_800_000_000;

    #[test]
    fn accepts_a_valid_signature_among_several() {
        let valid = sign(BODY, SECRET, NOW);
        let v1 = valid.split("v1=").nth(1).unwrap();
        let header = format!("t={NOW},v0=ignored,v1={},v1={v1}", "00".repeat(32));
        assert!(verify_signature(Some(&valid), BODY, SECRET, NOW));
        assert!(verify_signature(Some(&header), BODY, SECRET, NOW + 299));
    }

    #[test]
    fn refuses_everything_else() {
        let valid = sign(BODY, SECRET, NOW);
        assert!(!verify_signature(None, BODY, SECRET, NOW));
        assert!(!verify_signature(Some(" "), BODY, SECRET, NOW));
        assert!(!verify_signature(Some(&valid), BODY, "", NOW));
        assert!(!verify_signature(Some(&valid), BODY, "whsec_other", NOW));
        assert!(!verify_signature(Some(&valid), b"{}", SECRET, NOW));
        assert!(!verify_signature(Some(&valid), BODY, SECRET, NOW + 301), "too old");
        assert!(!verify_signature(Some(&valid), BODY, SECRET, NOW - 301), "from the future");
        assert!(!verify_signature(Some(&format!("t={NOW}")), BODY, SECRET, NOW), "no v1");
        assert!(!verify_signature(Some("v1=abcd"), BODY, SECRET, NOW), "no t");
        assert!(!verify_signature(Some("t=soon,v1=abcd"), BODY, SECRET, NOW), "bad t");
        assert!(!verify_signature(Some(&format!("t={NOW},v1=zz")), BODY, SECRET, NOW), "bad hex");
    }

    #[test]
    fn auth_failures_are_recognised() {
        let e = |status, t: Option<&str>| StripeError {
            status,
            error_type: t.map(str::to_string),
            code: None,
            message: String::new(),
        };
        assert!(e(401, None).is_auth_failure());
        assert!(e(400, Some("authentication_error")).is_auth_failure());
        assert!(!e(400, Some("invalid_request_error")).is_auth_failure());
    }
}
