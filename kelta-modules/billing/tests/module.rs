//! The billing module end to end through its router: signed platform calls, an in-memory
//! platform, and a local fake of the Stripe API.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::Router;
use axum::body::{Body, Bytes};
use axum::http::{HeaderMap, Request, StatusCode};
use axum::routing::post;
use http_body_util::BodyExt;
use kelta_billing::cache::Caches;
use kelta_billing::manifest::MODULE_ID;
use kelta_billing::stripe::{StripeClient, sign};
use kelta_billing::{Billing, PlatformSource, service};
use kelta_module::Call;
use kelta_module::client::ClientError;
use kelta_module::protocol::ModuleCredential;
use kelta_module::testing::{self, MemoryPlatform, PlatformKey, TENANT_ID, TestJwks, bearer, claims};
use serde_json::{Map, Value, json};
use tower::ServiceExt;

const MEMBER: &str = "123e4567-e89b-12d3-a456-426614174000";
const WEBHOOK_SECRET: &str = "whsec_test_placeholder";

struct Memory(MemoryPlatform);

impl PlatformSource for Memory {
    type P = MemoryPlatform;

    fn platform(&self, _call: &Call) -> Result<MemoryPlatform, ClientError> {
        Ok(self.0.clone())
    }
}

#[derive(Clone, Default)]
struct StripeLog(Arc<Mutex<Vec<(String, HeaderMap, String)>>>);

async fn fake_stripe() -> (String, StripeLog) {
    let log = StripeLog::default();
    let record = |path: &'static str, answer: Value| {
        let log = log.clone();
        post(move |headers: HeaderMap, body: Bytes| {
            let log = log.clone();
            let answer = answer.clone();
            async move {
                log.0.lock().unwrap().push((path.to_string(), headers, String::from_utf8_lossy(&body).to_string()));
                axum::Json(answer)
            }
        })
    };
    let app = Router::new()
        .route(
            "/v1/checkout/sessions",
            record("checkout", json!({"id": "cs_test_1", "url": "https://checkout.stripe.test/c/cs_test_1"})),
        )
        .route("/v1/billing_portal/sessions", record("portal", json!({"url": "https://billing.stripe.test/p/1"})));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (format!("http://{addr}/v1"), log)
}

struct Fixture {
    key: PlatformKey,
    platform: MemoryPlatform,
    router: Router,
    stripe: StripeLog,
}

async fn fixture() -> Fixture {
    let key = PlatformKey::generate("k1");
    let mut config = testing::config();
    config.module_id = MODULE_ID.into();
    let verifier = testing::verifier_with(config, TestJwks::with_keys(&[&key]));
    let platform = MemoryPlatform::new();
    let mut secrets = Map::new();
    secrets.insert("secretKey".into(), json!("sk_test_placeholder"));
    secrets.insert("webhookSecret".into(), json!(WEBHOOK_SECRET));
    let mut metadata = Map::new();
    metadata.insert("allowedReturnOrigins".into(), json!(["https://app.example.test"]));
    platform.set_credential(ModuleCredential {
        name: "stripe".into(),
        credential_type: "API_KEY".into(),
        secrets,
        metadata,
    });
    platform.insert(
        "billing_plans",
        json!({"code": "free", "name": "Free", "kind": "DEFAULT", "active": true, "sortOrder": 1,
               "entitlements": {"maxWatches": 1, "sms": false}}),
    );
    platform.insert(
        "billing_plans",
        json!({"code": "pro", "name": "Pro", "kind": "SUBSCRIPTION", "active": true, "sortOrder": 2,
               "stripePriceId": "price_pro", "entitlements": {"maxWatches": 10, "sms": true}}),
    );
    platform.insert(
        "billing_plans",
        json!({"code": "day", "name": "Day pass", "kind": "PASS", "active": true, "sortOrder": 3,
               "stripePriceId": "price_day", "passDurationDays": 1, "entitlements": {"maxWatches": 5}}),
    );
    let (stripe_url, stripe) = fake_stripe().await;
    let billing = Billing::new(
        Memory(platform.clone()),
        Caches::new(Duration::from_secs(60), Duration::from_secs(60)),
        StripeClient::new(stripe_url),
    );
    let router = service(Arc::new(verifier), billing).unwrap().router();
    Fixture { key, platform, router, stripe }
}

impl Fixture {
    /// Sends `method path` with `body`, signed with `claims` adjusted by `adjust`.
    async fn call(
        &self,
        method: &str,
        path: &str,
        body: &[u8],
        headers: &[(&str, &str)],
        adjust: impl FnOnce(&mut Value),
    ) -> (StatusCode, Value) {
        let mut claims = claims(method, path, body);
        claims["aud"] = json!(MODULE_ID);
        adjust(&mut claims);
        let mut request = Request::builder()
            .method(method)
            .uri(path)
            .header("authorization", bearer(&self.key.sign_own(&claims)))
            .header("kelta-access-token", "service-token");
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let response = self.router.clone().oneshot(request.body(Body::from(body.to_vec())).unwrap()).await.unwrap();
        let status = response.status();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
    }

    async fn as_member(&self, method: &str, path: &str, body: Value) -> (StatusCode, Value) {
        let body = if body.is_null() { Vec::new() } else { body.to_string().into_bytes() };
        self.call(method, path, &body, &[("content-type", "application/json")], |c| {
            c["act"] = json!({"sub": MEMBER, "user_type": "PORTAL"});
        })
        .await
    }

    async fn action(&self, key: &str, input: Value, idempotency_key: Option<&str>, actor: bool) -> Value {
        let body = json!({"tenantId": TENANT_ID, "input": input}).to_string();
        let headers: Vec<(&str, &str)> = idempotency_key.map(|k| ("idempotency-key", k)).into_iter().collect();
        let (status, answer) = self
            .call("POST", &format!("/kelta/v1/actions/{key}"), body.as_bytes(), &headers, |c| {
                if actor {
                    c["act"] = json!({"sub": MEMBER, "user_type": "PORTAL"});
                } else {
                    c.as_object_mut().unwrap().remove("act");
                }
            })
            .await;
        assert_eq!(status, StatusCode::OK, "{answer}");
        answer
    }

    async fn entitlements(&self) -> Value {
        let body = json!({"userId": MEMBER}).to_string();
        let (status, answer) =
            self.call("POST", "/kelta/v1/services/entitlements/resolve", body.as_bytes(), &[], |_| {}).await;
        assert_eq!(status, StatusCode::OK);
        answer
    }

    async fn webhook(&self, event: Value) -> (StatusCode, Value) {
        let body = event.to_string();
        let signature = sign(body.as_bytes(), WEBHOOK_SECRET, chrono::Utc::now().timestamp());
        self.call("POST", "/kelta/v1/webhooks", body.as_bytes(), &[("stripe-signature", &signature)], |c| {
            c.as_object_mut().unwrap().remove("act");
        })
        .await
    }

    async fn record_event(&self, collection: &str, data: Value) {
        let body = json!({"eventId": "e1", "type": "updated", "tenantId": TENANT_ID, "collection": collection,
                          "recordId": "r1", "timestamp": "2026-01-01T00:00:00Z", "data": data})
        .to_string();
        let (status, _) = self.call("POST", "/kelta/v1/events", body.as_bytes(), &[], |_| {}).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
    }
}

#[tokio::test]
async fn serves_its_manifest() {
    let f = fixture().await;
    let response = f
        .router
        .clone()
        .oneshot(Request::get("/.well-known/kelta-module.json").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let response =
        f.router.clone().oneshot(Request::get("/ui/kelta-billing.js").body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn lists_plans_without_processor_ids() {
    let f = fixture().await;
    let (status, body) = f.as_member("GET", "/kelta/v1/routes/plans", Value::Null).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["data"][0], json!({"code": "free", "name": "Free", "kind": "DEFAULT", "passDurationDays": null}));
    assert_eq!(body["data"].as_array().unwrap().len(), 3);
    assert!(!body.to_string().contains("price_pro"));
}

#[tokio::test]
async fn a_member_without_billing_is_on_the_default_plan() {
    let f = fixture().await;
    let (status, body) = f.as_member("GET", "/kelta/v1/routes/me", Value::Null).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body,
        json!({"plan": {"code": "free", "name": "Free", "kind": "DEFAULT"}, "subscription": null, "passes": [],
               "entitlements": {"maxWatches": 1, "sms": false}})
    );
    assert_eq!(
        f.entitlements().await,
        json!({"planCode": "free", "subscriptionStatus": null, "values": {"maxWatches": 1, "sms": false}})
    );
}

#[tokio::test]
async fn checkout_goes_to_stripe_with_the_member_and_plan() {
    let f = fixture().await;
    let request = json!({"planCode": "pro", "successUrl": "https://app.example.test/ok",
                         "cancelUrl": "https://app.example.test/cancel"});
    let (status, body) = f.as_member("POST", "/kelta/v1/routes/checkout-sessions", request).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body, json!({"url": "https://checkout.stripe.test/c/cs_test_1"}));
    let log = f.stripe.0.lock().unwrap();
    let (path, headers, form) = &log[0];
    assert_eq!(path, "checkout");
    assert_eq!(headers["authorization"], "Bearer sk_test_placeholder");
    assert_eq!(headers["stripe-version"], "2025-03-31.basil");
    let form: Vec<(String, String)> = url::form_urlencoded::parse(form.as_bytes()).into_owned().collect();
    let get = |k: &str| form.iter().find(|(key, _)| key == k).map(|(_, v)| v.as_str());
    assert_eq!(get("mode"), Some("subscription"));
    assert_eq!(get("line_items[0][price]"), Some("price_pro"));
    assert_eq!(get("client_reference_id"), Some(MEMBER));
    assert_eq!(get("metadata[tenantId]"), Some(TENANT_ID));
    assert_eq!(get("subscription_data[metadata][userId]"), Some(MEMBER));
    assert_eq!(get("customer"), None);
}

#[tokio::test]
async fn checkout_refuses_foreign_return_urls_and_unsellable_plans() {
    let f = fixture().await;
    let checkout = |plan: &str, success: &str| json!({"planCode": plan, "successUrl": success, "cancelUrl": "https://app.example.test/cancel"});
    let (status, body) =
        f.as_member("POST", "/kelta/v1/routes/checkout-sessions", checkout("pro", "https://evil.example.test/")).await;
    assert_eq!((status, body), (StatusCode::BAD_REQUEST, json!({"error": "Return URL is not allowed"})));
    let (_, body) =
        f.as_member("POST", "/kelta/v1/routes/checkout-sessions", checkout("free", "https://app.example.test/")).await;
    assert_eq!(body, json!({"error": "Plan is not purchasable"}));
    let (_, body) =
        f.as_member("POST", "/kelta/v1/routes/checkout-sessions", checkout("gold", "https://app.example.test/")).await;
    assert_eq!(body, json!({"error": "Unknown plan"}));
    let (_, body) = f.as_member("POST", "/kelta/v1/routes/checkout-sessions", json!({"planCode": "pro"})).await;
    assert_eq!(body, json!({"error": "planCode, successUrl and cancelUrl are required"}));
    assert!(f.stripe.0.lock().unwrap().is_empty());
}

#[tokio::test]
async fn actions_reuse_the_platform_idempotency_key_and_need_a_member() {
    let f = fixture().await;
    let input = json!({"planCode": "day", "successUrl": "https://app.example.test/ok",
                       "cancelUrl": "https://app.example.test/cancel"});
    let answer = f.action("create-checkout-session", input.clone(), Some("exec-1:Checkout"), true).await;
    assert_eq!(answer, json!({"success": true, "output": {"url": "https://checkout.stripe.test/c/cs_test_1"}}));
    {
        let log = f.stripe.0.lock().unwrap();
        assert_eq!(log[0].1["idempotency-key"], "kelta-billing:checkout:exec-1:Checkout");
        assert!(log[0].2.contains("mode=payment"));
    }
    let answer = f.action("create-checkout-session", input, None, false).await;
    assert_eq!(answer, json!({"success": false, "error": {"code": "BILLING_ERROR", "message": "No calling member"}}));
}

#[tokio::test]
async fn a_signed_webhook_moves_the_member_to_the_paid_plan_at_once() {
    let f = fixture().await;
    assert_eq!(f.entitlements().await["planCode"], "free"); // now cached
    let (status, body) = f
        .webhook(json!({"type": "customer.subscription.created", "data": {"object": {
            "id": "sub_1", "customer": "cus_1", "status": "active", "metadata": {"userId": MEMBER},
            "items": {"data": [{"price": {"id": "price_pro"}, "current_period_end": 1_900_000_000}]}}}}))
        .await;
    assert_eq!((status, body), (StatusCode::OK, json!({"eventType": "customer.subscription.created"})));
    assert_eq!(
        f.entitlements().await,
        json!({"planCode": "pro", "subscriptionStatus": "active", "values": {"maxWatches": 10, "sms": true}})
    );
}

#[tokio::test]
async fn an_unsigned_webhook_is_rejected() {
    let f = fixture().await;
    let body = br#"{"type":"customer.subscription.created"}"#;
    let (status, answer) =
        f.call("POST", "/kelta/v1/webhooks", body, &[("stripe-signature", "t=1,v1=00")], |_| {}).await;
    assert_eq!((status, answer), (StatusCode::UNAUTHORIZED, json!({"error": "Rejected"})));
    assert!(f.platform.writes().is_empty());
}

#[tokio::test]
async fn passes_add_to_the_plan_and_record_events_refresh_the_cache() {
    let f = fixture().await;
    let free = f.platform.records("billing_plans").into_iter().find(|p| p.attributes["code"] == "free").unwrap();
    let day = f.platform.records("billing_plans").into_iter().find(|p| p.attributes["code"] == "day").unwrap();
    assert_eq!(f.entitlements().await["values"]["maxWatches"], 1);

    // A pass written behind the module's back is seen once its record event arrives.
    f.platform.insert(
        "billing_passes",
        json!({"userId": MEMBER, "planId": day.id, "status": "ACTIVE",
                                               "stripeCheckoutSessionId": "cs_x", "expiresAt": "2999-01-01T00:00:00Z"}),
    );
    assert_eq!(f.entitlements().await["values"]["maxWatches"], 1, "still cached");
    f.record_event("billing_passes", json!({"userId": MEMBER})).await;
    assert_eq!(f.entitlements().await["values"]["maxWatches"], 6, "1 + 5, summed");

    // A plan edit refreshes every member of the tenant.
    let mut changed = Map::new();
    changed.insert("entitlements".into(), json!({"maxWatches": 2, "sms": false}));
    use kelta_module::client::Platform;
    f.platform.update("billing_plans", &free.id, changed).await.unwrap();
    f.record_event("billing_plans", json!({"code": "free"})).await;
    assert_eq!(f.entitlements().await["values"]["maxWatches"], 7);
}

#[tokio::test]
async fn the_quota_hook_refuses_a_member_at_their_limit() {
    let f = fixture().await;
    f.platform.insert(
        "billing_entitlement_rules",
        json!({"collectionName": "watches", "limitKey": "maxWatches",
                                                          "appliesTo": "ALL", "active": true}),
    );
    let hook = |record: Value| json!({"tenantId": TENANT_ID, "collection": "watches", "operation": "create", "record": record});
    let send = |body: Value| {
        let f = &f;
        async move {
            let body = body.to_string();
            f.call("POST", "/kelta/v1/hooks", body.as_bytes(), &[], |c| {
                c["act"] = json!({"sub": MEMBER, "user_type": "PORTAL"});
            })
            .await
        }
    };
    let (status, first) = send(hook(json!({"createdBy": MEMBER}))).await;
    assert_eq!((status, first), (StatusCode::OK, json!({"allowed": true})));
    f.platform.insert("watches", json!({"createdBy": MEMBER}));
    let (_, second) = send(hook(json!({"createdBy": MEMBER}))).await;
    assert_eq!(
        second,
        json!({"allowed": false, "errors": [{"field": null,
               "message": "You have reached your plan limit (1/1). Upgrade your plan to add more."}]})
    );
    // Updates are never capped.
    let update = json!({"tenantId": TENANT_ID, "collection": "watches", "operation": "update", "recordId": "w1",
                        "record": {"createdBy": MEMBER}});
    assert_eq!(send(update).await.1, json!({"allowed": true}));
}

#[tokio::test]
async fn portal_sessions_need_a_billing_account() {
    let f = fixture().await;
    let (status, body) = f
        .as_member("POST", "/kelta/v1/routes/portal-sessions", json!({"returnUrl": "https://app.example.test/"}))
        .await;
    assert_eq!((status, body), (StatusCode::BAD_REQUEST, json!({"error": "No billing account for this member"})));
    f.platform.insert("billing_customers", json!({"userId": MEMBER, "stripeCustomerId": "cus_1"}));
    let (status, body) = f
        .as_member("POST", "/kelta/v1/routes/portal-sessions", json!({"returnUrl": "https://app.example.test/"}))
        .await;
    assert_eq!((status, body), (StatusCode::OK, json!({"url": "https://billing.stripe.test/p/1"})));
    assert!(f.stripe.0.lock().unwrap()[0].2.contains("customer=cus_1"));
}

#[tokio::test]
async fn the_hourly_schedule_expires_due_passes() {
    let f = fixture().await;
    for (session, expiry) in
        [("a", "2020-01-01T00:00:00Z"), ("b", "2999-01-01T00:00:00Z"), ("c", "2021-01-01T00:00:00Z")]
    {
        f.platform.insert(
            "billing_passes",
            json!({"userId": MEMBER, "status": "ACTIVE",
                                                   "stripeCheckoutSessionId": session, "expiresAt": expiry}),
        );
    }
    f.platform.insert("billing_passes", json!({"userId": MEMBER, "status": "ACTIVE", "stripeCheckoutSessionId": "d"}));
    let answer = f.action("expire-passes", json!({}), Some("job:2026-01-01T00:00:00Z"), false).await;
    assert_eq!(answer, json!({"success": true, "output": {"expired": 2, "batchFull": false}}));
    let statuses: Vec<(String, String)> = f
        .platform
        .records("billing_passes")
        .into_iter()
        .map(|p| {
            (
                p.attributes["stripeCheckoutSessionId"].as_str().unwrap().to_string(),
                p.attributes["status"].as_str().unwrap().to_string(),
            )
        })
        .collect();
    assert_eq!(
        statuses,
        [("a", "EXPIRED"), ("b", "ACTIVE"), ("c", "EXPIRED"), ("d", "ACTIVE")]
            .map(|(a, b)| (a.to_string(), b.to_string()))
    );
    let answer = f.action("expire-passes", json!({"limit": 1}), None, false).await;
    assert_eq!(answer["output"], json!({"expired": 0, "batchFull": false}));
}
