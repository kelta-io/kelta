//! The router: verification before every handler, identity from claims only, and the protocol's
//! answer shapes.

mod common;

use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use common::*;
use http_body_util::BodyExt;
use kelta_module::protocol::{
    ActionRequest, ActionResponse, EntitlementsRequest, HookRequest, HookResponse, MemberEntitlements, RecordEvent,
};
use kelta_module::server::{RouteRequest, RouteResponse, WebhookRequest, WebhookResponse};
use kelta_module::{Call, HandlerError, ModuleHandlers, ModuleService};
use serde_json::{Map, Value, json};
use tower::ServiceExt;

#[derive(Default)]
struct Seen {
    calls: Vec<String>,
    actor: Option<String>,
    tenant: Option<String>,
    webhook_headers: Vec<String>,
    stripe_signature: Option<String>,
    route: Option<(String, Option<String>, String)>,
    idempotency_key: Option<String>,
}

#[derive(Clone, Default)]
struct Recorder(Arc<Mutex<Seen>>);

impl Recorder {
    fn seen(&self) -> std::sync::MutexGuard<'_, Seen> {
        self.0.lock().unwrap()
    }

    fn record(&self, what: &str, call: &Call) {
        let mut seen = self.seen();
        seen.calls.push(what.to_string());
        seen.actor = call.actor().map(|a| a.sub.clone());
        seen.tenant = Some(call.tenant_id().to_string());
        seen.idempotency_key = call.idempotency_key().map(str::to_string);
    }
}

impl ModuleHandlers for Recorder {
    async fn action(&self, call: Call, key: String, _request: ActionRequest) -> Result<ActionResponse, HandlerError> {
        self.record(&format!("action:{key}"), &call);
        let mut output = Map::new();
        output.insert("key".into(), json!(key));
        Ok(ActionResponse::success(output))
    }

    async fn hook(&self, call: Call, _request: HookRequest) -> Result<HookResponse, HandlerError> {
        self.record("hook", &call);
        Ok(HookResponse::allow())
    }

    async fn event(&self, call: Call, _event: RecordEvent) -> Result<(), HandlerError> {
        self.record("event", &call);
        Ok(())
    }

    async fn entitlements(&self, call: Call, request: EntitlementsRequest) -> Result<MemberEntitlements, HandlerError> {
        self.record("entitlements", &call);
        let mut values = Map::new();
        values.insert("for".into(), json!(request.user_id));
        Ok(MemberEntitlements { plan_code: Some("pro".into()), subscription_status: None, values })
    }

    async fn route(&self, call: Call, request: RouteRequest) -> Result<RouteResponse, HandlerError> {
        self.record("route", &call);
        let body = String::from_utf8_lossy(&request.body).to_string();
        self.seen().route = Some((request.path.clone(), request.raw_query.clone(), body));
        Ok(RouteResponse::new(StatusCode::CREATED, json!({ "query": request.query() })))
    }

    async fn webhook(&self, call: Call, request: WebhookRequest) -> Result<WebhookResponse, HandlerError> {
        self.record("webhook", &call);
        let mut seen = self.seen();
        seen.webhook_headers = request.headers.names().map(str::to_string).collect();
        seen.stripe_signature = request.headers.get("Stripe-Signature").map(str::to_string);
        Ok(WebhookResponse::new(StatusCode::ACCEPTED, None))
    }
}

struct Harness {
    key: PlatformKey,
    recorder: Recorder,
    router: axum::Router,
}

fn harness_with(jwks: Option<Arc<TestJwks>>) -> Harness {
    let key = PlatformKey::generate("k1");
    let jwks = jwks.unwrap_or_else(|| TestJwks::with_keys(&[&key]));
    let recorder = Recorder::default();
    let manifest =
        json!({ "manifestVersion": 2, "protocolVersion": 1, "id": MODULE_ID, "name": "Acme", "version": "3.1.4" });
    let router = ModuleService::new(Arc::new(verifier(jwks)), &manifest, recorder.clone())
        .platform_url("https://platform.example.test")
        .asset("/ui/bundle.js", "text/javascript", "console.log(1)")
        .router();
    Harness { key, recorder, router }
}

fn harness() -> Harness {
    harness_with(None)
}

impl Harness {
    async fn send(&self, request: Request<Body>) -> (StatusCode, Value) {
        let response = self.router.clone().oneshot(request).await.unwrap();
        let status = response.status();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        (status, if bytes.is_empty() { Value::Null } else { serde_json::from_slice(&bytes).unwrap_or(Value::Null) })
    }

    /// A request signed for exactly this method, path and body.
    fn signed(&self, method: &str, path: &str, body: &[u8]) -> axum::http::request::Builder {
        let token = self.key.sign_own(&claims(method, path, body));
        Request::builder()
            .method(method)
            .uri(path)
            .header("authorization", bearer(&token))
            .header("kelta-access-token", "service-token")
    }
}

fn hook_body(tenant: &str) -> Vec<u8> {
    json!({ "tenantId": tenant, "collection": "orders", "operation": "create", "record": {} }).to_string().into_bytes()
}

#[tokio::test]
async fn serves_the_manifest_and_assets_without_authentication() {
    let h = harness();
    let (status, manifest) = h.send(Request::get("/.well-known/kelta-module.json").body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(manifest["id"], MODULE_ID);
    let response = h.router.clone().oneshot(Request::get("/ui/bundle.js").body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["content-type"], "text/javascript");
}

#[tokio::test]
async fn refuses_every_protocol_endpoint_without_a_valid_assertion() {
    let h = harness();
    for (method, path) in [
        ("POST", "/kelta/v1/actions/ping"),
        ("POST", "/kelta/v1/hooks"),
        ("POST", "/kelta/v1/events"),
        ("POST", "/kelta/v1/services/entitlements/resolve"),
        ("GET", "/kelta/v1/routes/me"),
        ("POST", "/kelta/v1/webhooks"),
        ("POST", "/kelta/v1/lifecycle"),
        ("GET", "/kelta/v1/health"),
    ] {
        let unsigned = Request::builder().method(method).uri(path).body(Body::from("{}")).unwrap();
        let (status, body) = h.send(unsigned).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{method} {path}");
        assert_eq!(body, json!({ "error": "unauthorized" }));
    }
    assert!(h.recorder.seen().calls.is_empty(), "no handler ran");
}

#[tokio::test]
async fn takes_identity_from_the_claims_never_from_headers() {
    let h = harness();
    let body = hook_body(TENANT_ID);
    let request = h
        .signed("POST", "/kelta/v1/hooks", &body)
        .header("x-user-id", "attacker")
        .header("x-tenant-id", "another-tenant")
        .body(Body::from(body))
        .unwrap();
    let (status, answer) = h.send(request).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(answer, json!({ "allowed": true }));
    let seen = h.recorder.seen();
    assert_eq!(seen.actor.as_deref(), Some("user-1"));
    assert_eq!(seen.tenant.as_deref(), Some(TENANT_ID));
}

#[tokio::test]
async fn refuses_a_body_naming_another_tenant() {
    let h = harness();
    let body = hook_body("another-tenant");
    let (status, _) = h.send(h.signed("POST", "/kelta/v1/hooks", &body).body(Body::from(body)).unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert!(h.recorder.seen().calls.is_empty());
}

#[tokio::test]
async fn refuses_a_body_other_than_the_signed_one() {
    let h = harness();
    let signed_for = hook_body(TENANT_ID);
    let request = h.signed("POST", "/kelta/v1/hooks", &signed_for).body(Body::from(hook_body("x"))).unwrap();
    assert_eq!(h.send(request).await.0, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn refuses_a_replay_of_a_whole_request() {
    let h = harness();
    let body = hook_body(TENANT_ID);
    let token = h.key.sign_own(&claims("POST", "/kelta/v1/hooks", &body));
    let build = || {
        Request::post("/kelta/v1/hooks").header("authorization", bearer(&token)).body(Body::from(body.clone())).unwrap()
    };
    assert_eq!(h.send(build()).await.0, StatusCode::OK);
    assert_eq!(h.send(build()).await.0, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn answers_400_to_a_verified_but_malformed_body() {
    let h = harness();
    let body = br#"{"not":"a hook"}"#;
    let (status, answer) = h.send(h.signed("POST", "/kelta/v1/hooks", body).body(Body::from(&body[..])).unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(answer, json!({ "error": "malformed request body" }));
}

#[tokio::test]
async fn answers_503_when_the_platform_keys_are_unavailable() {
    let h = harness_with(Some(TestJwks::failing()));
    let body = hook_body(TENANT_ID);
    let (status, _) = h.send(h.signed("POST", "/kelta/v1/hooks", &body).body(Body::from(body)).unwrap()).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn runs_actions_with_the_idempotency_key() {
    let h = harness();
    let body = json!({ "tenantId": TENANT_ID, "input": {} }).to_string().into_bytes();
    let request = h
        .signed("POST", "/kelta/v1/actions/ping", &body)
        .header("idempotency-key", "job:1")
        .body(Body::from(body))
        .unwrap();
    let (status, answer) = h.send(request).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(answer, json!({ "success": true, "output": { "key": "ping" } }));
    assert_eq!(h.recorder.seen().idempotency_key.as_deref(), Some("job:1"));
}

#[tokio::test]
async fn answers_events_and_lifecycle_with_204() {
    let h = harness();
    let event = json!({ "eventId": "e1", "type": "created", "tenantId": TENANT_ID, "collection": "orders",
                        "recordId": "r1", "timestamp": "2026-01-01T00:00:00Z" })
    .to_string()
    .into_bytes();
    assert_eq!(
        h.send(h.signed("POST", "/kelta/v1/events", &event).body(Body::from(event)).unwrap()).await.0,
        StatusCode::NO_CONTENT
    );
    let lifecycle =
        json!({ "event": "installed", "tenantId": TENANT_ID, "moduleVersion": "3.1.4" }).to_string().into_bytes();
    let request = h.signed("POST", "/kelta/v1/lifecycle", &lifecycle).body(Body::from(lifecycle)).unwrap();
    assert_eq!(h.send(request).await.0, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn resolves_entitlements() {
    let h = harness();
    let body = json!({ "userId": "member-9" }).to_string().into_bytes();
    let request = h.signed("POST", "/kelta/v1/services/entitlements/resolve", &body).body(Body::from(body)).unwrap();
    let (status, answer) = h.send(request).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(answer, json!({ "planCode": "pro", "subscriptionStatus": null, "values": { "for": "member-9" } }));
}

#[tokio::test]
async fn passes_routes_their_path_query_and_body_and_returns_their_status() {
    let h = harness();
    let path = "/kelta/v1/routes/orders/recent?limit=5&q=a%20b";
    let request = h
        .signed("POST", path, b"{\"x\":1}")
        .header("content-type", "application/json")
        .body(Body::from("{\"x\":1}"))
        .unwrap();
    let (status, answer) = h.send(request).await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(answer, json!({ "query": { "limit": "5", "q": "a b" } }));
    let seen = h.recorder.seen();
    let (route, query, body) = seen.route.clone().unwrap();
    assert_eq!(route, "orders/recent");
    assert_eq!(query.as_deref(), Some("limit=5&q=a%20b"));
    assert_eq!(body, "{\"x\":1}");
}

#[tokio::test]
async fn gives_webhooks_the_provider_headers_but_not_platform_credentials() {
    let h = harness();
    let body = b"{\"id\":\"evt_1\"}";
    let request = h
        .signed("POST", "/kelta/v1/webhooks", body)
        .header("stripe-signature", "t=1,v1=abc")
        .header("x-user-id", "spoofed")
        .body(Body::from(&body[..]))
        .unwrap();
    let (status, _) = h.send(request).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    let seen = h.recorder.seen();
    assert_eq!(seen.stripe_signature.as_deref(), Some("t=1,v1=abc"));
    assert!(!seen.webhook_headers.iter().any(|h| h == "authorization" || h == "kelta-access-token"));
    // The forged header is visible only as untrusted provider input; the actor is the signed claim.
    assert_eq!(seen.actor.as_deref(), Some("user-1"));
}

#[tokio::test]
async fn health_reports_the_manifest_version() {
    let h = harness();
    let (status, answer) = h.send(h.signed("GET", "/kelta/v1/health", b"").body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(answer, json!({ "status": "ok", "version": "3.1.4" }));
}

#[tokio::test]
async fn unimplemented_handlers_answer_404() {
    struct Nothing;
    impl ModuleHandlers for Nothing {}
    let key = PlatformKey::generate("k1");
    let manifest = json!({ "id": MODULE_ID, "version": "1.0.0" });
    let router = ModuleService::new(Arc::new(verifier(TestJwks::with_keys(&[&key]))), &manifest, Nothing).router();
    let body = json!({ "userId": "u" }).to_string().into_bytes();
    let token = key.sign_own(&claims("POST", "/kelta/v1/services/entitlements/resolve", &body));
    let request = Request::post("/kelta/v1/services/entitlements/resolve")
        .header("authorization", bearer(&token))
        .body(Body::from(body))
        .unwrap();
    assert_eq!(router.oneshot(request).await.unwrap().status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn the_call_builds_a_platform_client_from_claims_and_token() {
    #[derive(Clone, Default)]
    struct Probe(Arc<Mutex<Option<String>>>);
    impl ModuleHandlers for Probe {
        async fn hook(&self, call: Call, _request: HookRequest) -> Result<HookResponse, HandlerError> {
            *self.0.lock().unwrap() = Some(format!("{:?}", call.platform().map_err(|e| e.to_string())));
            Ok(HookResponse::allow())
        }
    }
    let key = PlatformKey::generate("k1");
    let probe = Probe::default();
    let manifest = json!({ "id": MODULE_ID, "version": "1.0.0" });
    let router = ModuleService::new(Arc::new(verifier(TestJwks::with_keys(&[&key]))), &manifest, probe.clone())
        .platform_url("https://platform.example.test/")
        .router();
    let body = hook_body(TENANT_ID);
    let token = key.sign_own(&claims("POST", "/kelta/v1/hooks", &body));
    let request = Request::post("/kelta/v1/hooks")
        .header("authorization", bearer(&token))
        .header("kelta-access-token", "service-token")
        .body(Body::from(body))
        .unwrap();
    assert_eq!(router.oneshot(request).await.unwrap().status(), StatusCode::OK);
    let described = probe.0.lock().unwrap().clone().unwrap();
    assert!(described.contains("https://platform.example.test/acme"), "{described}");
    assert!(!described.contains("service-token"), "the token never shows in Debug");
}
