//! The module protocol's HTTP surface as an axum [`Router`].
//!
//! [`ModuleService::router`] serves the manifest and public assets without authentication, and
//! every `/kelta/v1/*` endpoint only after [`AssertionVerifier::verify`] accepted the request. A
//! refused request is answered `401 {"error":"unauthorized"}` (or `503` when the platform's keys
//! cannot be fetched) and never reaches a handler; the reason is logged, the token never is.
//!
//! # Identity comes from the signed assertion only
//!
//! A handler learns who it serves from [`Call`]: tenant, install and acting user, all read from
//! the verified claims. No API here reads identity from a header. Webhook headers in particular
//! are whatever the third party — or anyone who can reach the platform's public webhook URL —
//! sent, a caller-made `X-User-Id` included: they are exposed as [`UntrustedHeaders`] for the
//! provider's own signature check and nothing else.

use std::collections::HashMap;
use std::fmt;
use std::future::Future;
use std::net::SocketAddr;
use std::sync::Arc;

use axum::Router;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::header::{AUTHORIZATION, CACHE_CONTROL, CONTENT_TYPE};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::routing::{any, get, post};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value, json};

use crate::assertion::{AssertionVerifier, IncomingRequest, VerifiedAssertion, VerifyError};
use crate::client::{ClientError, PlatformClient};
use crate::protocol::{
    ActionRequest, ActionResponse, Actor, EntitlementsRequest, Health, HealthStatus, HookRequest, HookResponse,
    LifecycleEvent, MemberEntitlements, RecordEvent,
};

/// The header carrying the install's service-principal token.
pub const ACCESS_TOKEN_HEADER: &str = "kelta-access-token";

/// One verified platform call: who it is for, and how to call the platform back.
///
/// Built only by the router, from a verified assertion.
#[derive(Clone)]
pub struct Call {
    assertion: VerifiedAssertion,
    access_token: Option<String>,
    idempotency_key: Option<String>,
    traceparent: Option<String>,
    platform_url: Option<Arc<str>>,
    http: reqwest::Client,
}

impl fmt::Debug for Call {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Call")
            .field("assertion", &self.assertion)
            .field("idempotency_key", &self.idempotency_key)
            .finish_non_exhaustive()
    }
}

impl Call {
    /// The tenant this call serves (`tenant_id` claim).
    pub fn tenant_id(&self) -> &str {
        &self.assertion.tenant_id
    }

    /// The tenant's URL slug (`tenant_slug` claim).
    pub fn tenant_slug(&self) -> Option<&str> {
        self.assertion.tenant_slug.as_deref()
    }

    /// The install id (`sub` claim).
    pub fn install_id(&self) -> &str {
        &self.assertion.install_id
    }

    /// The user whose action caused this call (`act` claim). `None` for flow steps without a
    /// request caller, schedules, webhooks, events and lifecycle calls.
    pub fn actor(&self) -> Option<&Actor> {
        self.assertion.actor.as_ref()
    }

    /// The whole verified assertion.
    pub fn assertion(&self) -> &VerifiedAssertion {
        &self.assertion
    }

    /// The `Idempotency-Key` the platform sent (actions and schedules): reuse it for side effects
    /// so a retried call does not repeat them.
    pub fn idempotency_key(&self) -> Option<&str> {
        self.idempotency_key.as_deref()
    }

    /// The W3C `traceparent` the platform sent.
    pub fn traceparent(&self) -> Option<&str> {
        self.traceparent.as_deref()
    }

    /// A client for the tenant's platform API with this call's `Kelta-Access-Token`. Use it within
    /// this request only.
    pub fn platform(&self) -> Result<PlatformClient, ClientError> {
        let url = self
            .platform_url
            .as_deref()
            .ok_or_else(|| ClientError::NotConfigured("no platform URL configured".into()))?;
        let slug =
            self.tenant_slug().ok_or_else(|| ClientError::NotConfigured("the call names no tenant slug".into()))?;
        let token = self
            .access_token
            .as_deref()
            .ok_or_else(|| ClientError::NotConfigured("the call carries no access token".into()))?;
        PlatformClient::new(self.http.clone(), url, slug, token, self.traceparent.clone())
    }

    /// A call for tests of module code, with no verification behind it. Only built with the
    /// `testing` feature; never enable it in a module's normal dependencies.
    #[cfg(feature = "testing")]
    pub fn for_testing(
        assertion: VerifiedAssertion,
        access_token: Option<String>,
        idempotency_key: Option<String>,
        platform_url: Option<String>,
    ) -> Self {
        Self {
            assertion,
            access_token,
            idempotency_key,
            traceparent: None,
            platform_url: platform_url.map(Arc::from),
            http: PlatformClient::default_http(),
        }
    }
}

/// A handler's failure, answered with `status` and `{"error": message}`. The platform treats any
/// non-2xx as the module being unavailable, except on routes and webhooks, where the status goes
/// back to the caller.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{status}: {message}")]
pub struct HandlerError {
    pub status: StatusCode,
    pub message: String,
}

impl HandlerError {
    pub fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self { status, message: message.into() }
    }

    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, message)
    }

    pub fn unavailable(message: impl Into<String>) -> Self {
        Self::new(StatusCode::SERVICE_UNAVAILABLE, message)
    }
}

impl IntoResponse for HandlerError {
    fn into_response(self) -> Response {
        json_response(self.status, &json!({ "error": self.message }))
    }
}

/// A call to a module HTTP route (`/kelta/v1/routes/{path}`).
#[derive(Debug, Clone)]
pub struct RouteRequest {
    /// The caller's method.
    pub method: Method,
    /// The route path, relative, as the manifest declares it (`plans`, `orders/123`).
    pub path: String,
    /// The caller's raw query string, without `?`.
    pub raw_query: Option<String>,
    /// The caller's `Content-Type`.
    pub content_type: Option<String>,
    /// The caller's body.
    pub body: Bytes,
}

impl RouteRequest {
    /// The query parameters, decoded; a repeated name keeps its last value.
    pub fn query(&self) -> Map<String, Value> {
        let mut out = Map::new();
        if let Some(raw) = &self.raw_query {
            for (k, v) in url::form_urlencoded::parse(raw.as_bytes()) {
                out.insert(k.into_owned(), Value::String(v.into_owned()));
            }
        }
        out
    }

    /// The body as a JSON object; `None` when empty. A body that is not a JSON object is an error.
    pub fn json_object(&self) -> Result<Option<Map<String, Value>>, HandlerError> {
        if self.body.iter().all(u8::is_ascii_whitespace) {
            return Ok(None);
        }
        match serde_json::from_slice::<Value>(&self.body) {
            Ok(Value::Object(map)) => Ok(Some(map)),
            Ok(Value::Null) => Ok(None),
            _ => Err(HandlerError::bad_request("the body must be a JSON object")),
        }
    }
}

/// A route's answer: the status and JSON body go back to the caller (a 5xx becomes `502`).
#[derive(Debug, Clone, PartialEq)]
pub struct RouteResponse {
    pub status: StatusCode,
    pub body: Value,
}

impl RouteResponse {
    pub fn new(status: StatusCode, body: Value) -> Self {
        Self { status, body }
    }

    pub fn ok(body: Value) -> Self {
        Self::new(StatusCode::OK, body)
    }
}

/// Request headers forwarded with a third-party webhook.
///
/// **Untrusted.** The platform forwards these from whoever posted to its public webhook URL. Use
/// them only as input to the provider's own signature check (for example `stripe-signature`).
/// Never derive tenant, user or permissions from them: a caller can send any `x-*` header,
/// `x-user-id` and `x-tenant-id` included. The tenant is [`Call::tenant_id`], from the assertion.
#[derive(Clone, Default)]
pub struct UntrustedHeaders(HeaderMap);

impl UntrustedHeaders {
    /// A header value as text (names are case-insensitive).
    pub fn get(&self, name: &str) -> Option<&str> {
        self.0.get(name).and_then(|v| v.to_str().ok())
    }

    /// All header names present.
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.0.keys().map(|k| k.as_str())
    }

    /// Wraps a header map (tests, or a custom transport).
    pub fn from_map(map: HeaderMap) -> Self {
        Self(map)
    }
}

impl fmt::Debug for UntrustedHeaders {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_list().entries(self.names()).finish()
    }
}

/// An inbound third-party webhook (`/kelta/v1/webhooks`).
#[derive(Debug, Clone)]
pub struct WebhookRequest {
    /// The raw body bytes, exactly as the provider sent them: verify the provider signature over
    /// these before parsing.
    pub body: Bytes,
    /// The forwarded headers. See [`UntrustedHeaders`].
    pub headers: UntrustedHeaders,
}

/// A webhook's answer; its status goes back to the provider (answer non-2xx to make it retry).
#[derive(Debug, Clone, PartialEq)]
pub struct WebhookResponse {
    pub status: StatusCode,
    pub body: Option<Value>,
}

impl WebhookResponse {
    pub fn new(status: StatusCode, body: Option<Value>) -> Self {
        Self { status, body }
    }
}

/// A module's logic, one method per protocol endpoint. Every method has a default, so a module
/// implements only what its manifest declares.
pub trait ModuleHandlers: Send + Sync + 'static {
    /// `POST /kelta/v1/actions/{key}`: run a flow action. Answer a business failure with
    /// [`ActionResponse::failure`], not an error.
    fn action(
        &self,
        _call: Call,
        key: String,
        _request: ActionRequest,
    ) -> impl Future<Output = Result<ActionResponse, HandlerError>> + Send {
        async move { Err(HandlerError::not_found(format!("unknown action '{key}'"))) }
    }

    /// `POST /kelta/v1/hooks`: decide a record write.
    fn hook(
        &self,
        _call: Call,
        _request: HookRequest,
    ) -> impl Future<Output = Result<HookResponse, HandlerError>> + Send {
        async { Ok(HookResponse::allow()) }
    }

    /// `POST /kelta/v1/events`: a record event (at least once, unordered).
    fn event(&self, _call: Call, _event: RecordEvent) -> impl Future<Output = Result<(), HandlerError>> + Send {
        async { Ok(()) }
    }

    /// `POST /kelta/v1/services/entitlements/resolve`.
    fn entitlements(
        &self,
        _call: Call,
        _request: EntitlementsRequest,
    ) -> impl Future<Output = Result<MemberEntitlements, HandlerError>> + Send {
        async { Err(HandlerError::not_found("this module serves no entitlements")) }
    }

    /// `/kelta/v1/routes/{path}`: a module HTTP route.
    fn route(
        &self,
        _call: Call,
        request: RouteRequest,
    ) -> impl Future<Output = Result<RouteResponse, HandlerError>> + Send {
        async move { Err(HandlerError::not_found(format!("no route '{}'", request.path))) }
    }

    /// `POST /kelta/v1/webhooks`: a third-party webhook.
    fn webhook(
        &self,
        _call: Call,
        _request: WebhookRequest,
    ) -> impl Future<Output = Result<WebhookResponse, HandlerError>> + Send {
        async { Err(HandlerError::not_found("this module takes no webhooks")) }
    }

    /// `POST /kelta/v1/lifecycle`. Must be idempotent.
    fn lifecycle(&self, _call: Call, _event: LifecycleEvent) -> impl Future<Output = Result<(), HandlerError>> + Send {
        async { Ok(()) }
    }

    /// `GET /kelta/v1/health`. The router fills an empty `version` from the manifest.
    fn health(&self, _call: Call) -> impl Future<Output = Health> + Send {
        async { Health { status: HealthStatus::Ok, version: String::new(), details: None } }
    }
}

struct Asset {
    content_type: HeaderValue,
    bytes: Bytes,
}

struct Inner<H> {
    verifier: Arc<AssertionVerifier>,
    handlers: H,
    manifest: Bytes,
    version: String,
    platform_url: Option<Arc<str>>,
    http: reqwest::Client,
    assets: HashMap<String, Asset>,
}

/// A module's HTTP service: verifier, manifest, handlers and public assets.
pub struct ModuleService<H> {
    inner: Inner<H>,
}

impl<H: ModuleHandlers> ModuleService<H> {
    /// A service answering with `handlers`, verifying with `verifier` and serving `manifest` at
    /// `/.well-known/kelta-module.json`.
    pub fn new(verifier: Arc<AssertionVerifier>, manifest: &Value, handlers: H) -> Self {
        let version = manifest.get("version").and_then(Value::as_str).unwrap_or_default().to_string();
        Self {
            inner: Inner {
                verifier,
                handlers,
                manifest: Bytes::from(manifest.to_string()),
                version,
                platform_url: None,
                http: PlatformClient::default_http(),
                assets: HashMap::new(),
            },
        }
    }

    /// The platform base URL [`Call::platform`] calls (`{url}/{tenantSlug}/api/...`).
    pub fn platform_url(mut self, url: impl Into<String>) -> Self {
        let url = url.into();
        self.inner.platform_url = Some(Arc::from(url.trim().trim_end_matches('/')));
        self
    }

    /// The HTTP client platform calls use.
    pub fn http_client(mut self, http: reqwest::Client) -> Self {
        self.inner.http = http;
        self
    }

    /// Serves `bytes` at `path` without authentication (the admin UI bundle).
    pub fn asset(mut self, path: impl Into<String>, content_type: &'static str, bytes: impl Into<Bytes>) -> Self {
        self.inner
            .assets
            .insert(path.into(), Asset { content_type: HeaderValue::from_static(content_type), bytes: bytes.into() });
        self
    }

    /// The router.
    pub fn router(self) -> Router {
        let asset_paths: Vec<String> = self.inner.assets.keys().cloned().collect();
        let state = Arc::new(self.inner);
        let mut router = Router::new()
            .route("/.well-known/kelta-module.json", get(serve_manifest::<H>))
            .route("/kelta/v1/actions/{key}", post(action::<H>))
            .route("/kelta/v1/hooks", post(hook::<H>))
            .route("/kelta/v1/events", post(event::<H>))
            .route("/kelta/v1/services/entitlements/resolve", post(entitlements::<H>))
            .route("/kelta/v1/routes/{*path}", any(route::<H>))
            .route("/kelta/v1/webhooks", post(webhook::<H>))
            .route("/kelta/v1/lifecycle", post(lifecycle::<H>))
            .route("/kelta/v1/health", get(health::<H>));
        for path in asset_paths {
            router = router.route(&path, get(serve_asset::<H>));
        }
        router.layer(axum::middleware::from_fn(access_log)).with_state(state)
    }
}

/// Logs each request at `debug` under the `kelta_module::access` target (`RUST_LOG=kelta_module=debug`):
/// method, path, status and elapsed microseconds. Never the query string or a header, which carry
/// caller input and the access token.
async fn access_log(request: axum::extract::Request, next: axum::middleware::Next) -> Response {
    if !tracing::enabled!(target: "kelta_module::access", tracing::Level::DEBUG) {
        return next.run(request).await;
    }
    let method = request.method().clone();
    let path = request.uri().path().to_owned();
    let started = std::time::Instant::now();
    let response = next.run(request).await;
    let elapsed_us = u64::try_from(started.elapsed().as_micros()).unwrap_or(u64::MAX);
    tracing::debug!(
        target: "kelta_module::access",
        %method,
        %path,
        status = response.status().as_u16(),
        elapsed_us,
        "request"
    );
    response
}

/// Serves `router` on `addr` until `SIGTERM` or Ctrl-C, then drains in-flight requests.
pub async fn serve(router: Router, addr: SocketAddr) -> std::io::Result<()> {
    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!(%addr, "module listening");
    axum::serve(listener, router).with_graceful_shutdown(shutdown_signal()).await
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        () = ctrl_c => {},
        () = terminate => {},
    }
}

type AppState<H> = State<Arc<Inner<H>>>;

async fn serve_manifest<H: ModuleHandlers>(State(app): AppState<H>) -> Response {
    let mut response = app.manifest.clone().into_response();
    let headers = response.headers_mut();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    response
}

async fn serve_asset<H: ModuleHandlers>(State(app): AppState<H>, uri: Uri) -> Response {
    match app.assets.get(uri.path()) {
        Some(asset) => {
            let mut response = asset.bytes.clone().into_response();
            response.headers_mut().insert(CONTENT_TYPE, asset.content_type.clone());
            response
        }
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn action<H: ModuleHandlers>(
    State(app): AppState<H>,
    Path(key): Path<String>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let request: ActionRequest = match parse(&body) {
        Ok(request) => request,
        Err(bad) => return *bad,
    };
    if let Err(refused) = same_tenant(&call, &request.tenant_id) {
        return *refused;
    }
    let carries_task = request.task.is_some();
    match app.handlers.action(call, key, request).await {
        // A long-running action took on its task: 202, no body (part 9h).
        Ok(answer) if answer.is_accepted() && carries_task => StatusCode::ACCEPTED.into_response(),
        Ok(answer) if answer.is_accepted() => {
            HandlerError::internal("ActionResponse::accepted() answers only a request that carries a task")
                .into_response()
        }
        result => respond(result),
    }
}

async fn hook<H: ModuleHandlers>(
    State(app): AppState<H>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let request: HookRequest = match parse(&body) {
        Ok(request) => request,
        Err(bad) => return *bad,
    };
    if let Err(refused) = same_tenant(&call, &request.tenant_id) {
        return *refused;
    }
    respond(app.handlers.hook(call, request).await)
}

async fn event<H: ModuleHandlers>(
    State(app): AppState<H>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let event: RecordEvent = match parse(&body) {
        Ok(event) => event,
        Err(bad) => return *bad,
    };
    if let Err(refused) = same_tenant(&call, &event.tenant_id) {
        return *refused;
    }
    match app.handlers.event(call, event).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(e) => e.into_response(),
    }
}

async fn entitlements<H: ModuleHandlers>(
    State(app): AppState<H>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let request: EntitlementsRequest = match parse(&body) {
        Ok(request) => request,
        Err(bad) => return *bad,
    };
    respond(app.handlers.entitlements(call, request).await)
}

async fn route<H: ModuleHandlers>(
    State(app): AppState<H>,
    Path(path): Path<String>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let request = RouteRequest {
        method,
        path,
        raw_query: uri.query().map(str::to_string),
        content_type: headers.get(CONTENT_TYPE).and_then(|v| v.to_str().ok()).map(str::to_string),
        body,
    };
    match app.handlers.route(call, request).await {
        Ok(answer) => json_response(answer.status, &answer.body),
        Err(e) => e.into_response(),
    }
}

async fn webhook<H: ModuleHandlers>(
    State(app): AppState<H>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let mut forwarded = headers;
    // The assertion and the access token are platform material, not the provider's.
    forwarded.remove(AUTHORIZATION);
    forwarded.remove(ACCESS_TOKEN_HEADER);
    let request = WebhookRequest { body, headers: UntrustedHeaders(forwarded) };
    match app.handlers.webhook(call, request).await {
        Ok(answer) => match answer.body {
            Some(body) => json_response(answer.status, &body),
            None => answer.status.into_response(),
        },
        Err(e) => e.into_response(),
    }
}

async fn lifecycle<H: ModuleHandlers>(
    State(app): AppState<H>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let event: LifecycleEvent = match parse(&body) {
        Ok(event) => event,
        Err(bad) => return *bad,
    };
    if let Err(refused) = same_tenant(&call, &event.tenant_id) {
        return *refused;
    }
    match app.handlers.lifecycle(call, event).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(e) => e.into_response(),
    }
}

async fn health<H: ModuleHandlers>(
    State(app): AppState<H>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let call = match authenticate(&app, &method, &uri, &headers, &body).await {
        Ok(call) => call,
        Err(refused) => return *refused,
    };
    let mut answer = app.handlers.health(call).await;
    if answer.version.is_empty() {
        answer.version = app.version.clone();
    }
    json_response(StatusCode::OK, &answer)
}

async fn authenticate<H>(
    app: &Inner<H>,
    method: &Method,
    uri: &Uri,
    headers: &HeaderMap,
    body: &[u8],
) -> Result<Call, Box<Response>> {
    let path_and_query = uri.path_and_query().map(|p| p.as_str()).unwrap_or_else(|| uri.path());
    let request = IncomingRequest {
        method: method.as_str(),
        path_and_query,
        authorization: headers.get(AUTHORIZATION).and_then(|v| v.to_str().ok()),
        body,
    };
    match app.verifier.verify(request).await {
        Ok(assertion) => Ok(Call {
            assertion,
            access_token: header(headers, ACCESS_TOKEN_HEADER),
            idempotency_key: header(headers, "idempotency-key"),
            traceparent: header(headers, "traceparent"),
            platform_url: app.platform_url.clone(),
            http: app.http.clone(),
        }),
        Err(VerifyError::JwksUnavailable(reason)) => {
            tracing::error!(path = uri.path(), reason = %reason, "cannot verify module calls: the platform JWKS is unavailable");
            Err(Box::new(json_response(StatusCode::SERVICE_UNAVAILABLE, &json!({ "error": "unavailable" }))))
        }
        Err(e) => {
            tracing::warn!(path = uri.path(), reason = %e, "refused an unverified module call");
            Err(Box::new(unauthorized()))
        }
    }
}

/// The body's `tenantId` must be the assertion's: a valid assertion for one tenant never carries
/// another tenant's payload.
fn same_tenant(call: &Call, body_tenant: &str) -> Result<(), Box<Response>> {
    if body_tenant == call.tenant_id() {
        Ok(())
    } else {
        tracing::warn!("refused a module call whose body names another tenant than its assertion");
        Err(Box::new(unauthorized()))
    }
}

fn header(headers: &HeaderMap, name: &str) -> Option<String> {
    headers.get(name).and_then(|v| v.to_str().ok()).map(str::trim).filter(|v| !v.is_empty()).map(str::to_string)
}

fn parse<T: DeserializeOwned>(body: &[u8]) -> Result<T, Box<Response>> {
    serde_json::from_slice(body).map_err(|e| {
        tracing::warn!(error = %e, "refused a malformed module call body");
        Box::new(json_response(StatusCode::BAD_REQUEST, &json!({ "error": "malformed request body" })))
    })
}

fn respond<T: Serialize>(result: Result<T, HandlerError>) -> Response {
    match result {
        Ok(answer) => json_response(StatusCode::OK, &answer),
        Err(e) => e.into_response(),
    }
}

fn unauthorized() -> Response {
    json_response(StatusCode::UNAUTHORIZED, &json!({ "error": "unauthorized" }))
}

fn json_response<T: Serialize + ?Sized>(status: StatusCode, body: &T) -> Response {
    match serde_json::to_vec(body) {
        Ok(bytes) => {
            let mut response = (status, bytes).into_response();
            response.headers_mut().insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
            response
        }
        Err(e) => {
            tracing::error!(error = %e, "could not serialize a module answer");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}
