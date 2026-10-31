//! `kelta-billing`: Stripe-backed portal billing as an external Kelta module (module protocol v1).
//!
//! The Rust port of the Java JAR module (`kelta-modules/billing-jar`): the same collections,
//! actions, entitlement rules, quota hook and Stripe webhook, served over HTTP. Identity comes
//! from the verified platform assertion only; Stripe secrets come from the tenant's `stripe`
//! credential through the platform, per request, and are never stored or logged.

pub mod actions;
pub mod cache;
pub mod config;
pub mod entitlements;
pub mod manifest;
pub mod quota;
pub mod store;
pub mod stripe;
pub mod values;
pub mod webhook;

use std::sync::Arc;

use axum::http::StatusCode;
use chrono::Utc;
use kelta_module::client::{ClientError, Platform, PlatformClient, Resource};
use kelta_module::manifest::ManifestError;
use kelta_module::protocol::{
    ActionRequest, ActionResponse, EntitlementsRequest, HookRequest, HookResponse, LifecycleEvent, MemberEntitlements,
    Operation, RecordEvent,
};
use kelta_module::server::{RouteRequest, RouteResponse, WebhookRequest, WebhookResponse};
use kelta_module::{AssertionVerifier, Call, HandlerError, ModuleHandlers, ModuleService};
use serde_json::{Map, Value, json};

use crate::actions::ActionCall;
use crate::cache::Caches;
use crate::quota::QuotaSource;
use crate::store::{BillingStore, ENTITLEMENT_RULES, PASSES, PLANS, SUBSCRIPTIONS};
use crate::stripe::StripeClient;
use crate::values::text_of;
use crate::webhook::Outcome;

/// The error code of every action failure.
pub const ACTION_FAILURE_CODE: &str = "BILLING_ERROR";

/// One tenant's billing data and caches, for the length of one call.
pub struct Tenant<'a, P> {
    pub platform: &'a P,
    pub caches: &'a Caches,
    pub tenant_id: &'a str,
}

impl<'a, P: Platform> Tenant<'a, P> {
    pub fn store(&self) -> BillingStore<'a, P> {
        BillingStore::new(self.platform)
    }
}

impl<P: Platform> QuotaSource for Tenant<'_, P> {
    async fn rules(&self) -> Result<Arc<Vec<Resource>>, ClientError> {
        self.caches.rules(self.tenant_id, self.store().active_rules()).await
    }

    async fn entitlements(&self, user: &str) -> Result<MemberEntitlements, ClientError> {
        let store = self.store();
        self.caches.entitlements(self.tenant_id, user, entitlements::resolve(&store, user, Utc::now())).await
    }
}

/// How the module reaches the platform for one call.
pub trait PlatformSource: Send + Sync + 'static {
    type P: Platform;
    fn platform(&self, call: &Call) -> Result<Self::P, ClientError>;
}

/// The real platform: the call's `Kelta-Access-Token` against the configured platform URL.
pub struct FromCall;

impl PlatformSource for FromCall {
    type P = PlatformClient;

    fn platform(&self, call: &Call) -> Result<PlatformClient, ClientError> {
        call.platform()
    }
}

/// The module's protocol handlers.
pub struct Billing<S> {
    source: S,
    caches: Caches,
    stripe: StripeClient,
}

impl<S: PlatformSource> Billing<S> {
    pub fn new(source: S, caches: Caches, stripe: StripeClient) -> Self {
        Self { source, caches, stripe }
    }

    fn platform(&self, call: &Call) -> Result<S::P, HandlerError> {
        self.source.platform(call).map_err(|e| {
            tracing::error!(error = %e, "cannot call the platform for this request");
            HandlerError::unavailable("the platform cannot be called")
        })
    }

    fn tenant<'a>(&'a self, platform: &'a S::P, call: &'a Call) -> Tenant<'a, S::P> {
        Tenant { platform, caches: &self.caches, tenant_id: call.tenant_id() }
    }
}

/// The module's HTTP service: manifest, admin UI bundle and protocol handlers. Set its platform
/// URL before taking the router.
pub fn service<S: PlatformSource>(
    verifier: Arc<AssertionVerifier>,
    billing: Billing<S>,
) -> Result<ModuleService<Billing<S>>, ManifestError> {
    let manifest = manifest::manifest()?;
    Ok(ModuleService::new(verifier, &manifest, billing).asset(
        manifest::UI_BUNDLE_PATH,
        "text/javascript; charset=utf-8",
        manifest::UI_BUNDLE,
    ))
}

/// The action behind a route, and the one method it answers.
fn route_action(path: &str) -> Option<(&'static str, &'static str)> {
    match path {
        "plans" => Some(("list-plans", "GET")),
        "me" => Some(("me", "GET")),
        "checkout-sessions" => Some(("create-checkout-session", "POST")),
        "portal-sessions" => Some(("create-portal-session", "POST")),
        _ => None,
    }
}

/// The members a billing record event concerns; `None` when the event does not say.
fn event_members(event: &RecordEvent) -> Option<Vec<String>> {
    if event.payload_omitted {
        return None;
    }
    let members: Vec<String> =
        [&event.data, &event.previous_data].into_iter().flatten().filter_map(|r| text_of(r, "userId")).collect();
    (!members.is_empty()).then_some(members)
}

impl<S: PlatformSource> ModuleHandlers for Billing<S> {
    async fn action(&self, call: Call, key: String, request: ActionRequest) -> Result<ActionResponse, HandlerError> {
        let platform = self.platform(&call)?;
        let tenant = self.tenant(&platform, &call);
        let action = ActionCall {
            tenant: &tenant,
            stripe: &self.stripe,
            user_id: call.actor().map(|a| a.sub.as_str()),
            input: &request.input,
            idempotency_key: call.idempotency_key(),
        };
        match actions::run(&key, &action).await {
            None => Err(HandlerError::not_found(format!("unknown action '{key}'"))),
            Some(Ok(output)) => Ok(ActionResponse::success(output)),
            Some(Err(message)) => Ok(ActionResponse::failure(ACTION_FAILURE_CODE, message)),
        }
    }

    async fn hook(&self, call: Call, request: HookRequest) -> Result<HookResponse, HandlerError> {
        if request.operation != Operation::Create {
            return Ok(HookResponse::allow());
        }
        let Ok(platform) = self.platform(&call) else {
            return Ok(HookResponse::allow()); // fail open, like every other quota fault
        };
        let tenant = self.tenant(&platform, &call);
        Ok(quota::before_create(&platform, &tenant, &request.collection, request.record.as_ref(), call.actor()).await)
    }

    async fn event(&self, call: Call, event: RecordEvent) -> Result<(), HandlerError> {
        let tenant = call.tenant_id();
        match event.collection.as_str() {
            PLANS => self.caches.invalidate_tenant_entitlements(tenant),
            ENTITLEMENT_RULES => self.caches.invalidate_rules(tenant),
            SUBSCRIPTIONS | PASSES => match event_members(&event) {
                Some(members) => members.iter().for_each(|m| self.caches.invalidate_member(tenant, m)),
                None => self.caches.invalidate_tenant_entitlements(tenant),
            },
            _ => {}
        }
        Ok(())
    }

    async fn entitlements(&self, call: Call, request: EntitlementsRequest) -> Result<MemberEntitlements, HandlerError> {
        let platform = self.platform(&call)?;
        let tenant = self.tenant(&platform, &call);
        // Unavailable rather than empty on a fault: the platform then falls back to EMPTY itself.
        tenant.entitlements(&request.user_id).await.map_err(|e| {
            tracing::error!(error = %e, "could not resolve entitlements");
            HandlerError::unavailable("could not resolve entitlements")
        })
    }

    async fn route(&self, call: Call, request: RouteRequest) -> Result<RouteResponse, HandlerError> {
        let Some((key, method)) = route_action(&request.path) else {
            return Err(HandlerError::not_found(format!("no route '{}'", request.path)));
        };
        if request.method.as_str() != method {
            return Err(HandlerError::new(StatusCode::METHOD_NOT_ALLOWED, "method not allowed"));
        }
        // Query parameters, overlaid by the JSON body: the JAR route contract.
        let mut input: Map<String, Value> = request.query();
        if let Some(body) = request.json_object()? {
            input.extend(body);
        }
        let platform = self.platform(&call)?;
        let tenant = self.tenant(&platform, &call);
        let action = ActionCall {
            tenant: &tenant,
            stripe: &self.stripe,
            user_id: call.actor().map(|a| a.sub.as_str()),
            input: &input,
            idempotency_key: None,
        };
        match actions::run(key, &action).await {
            Some(Ok(output)) => Ok(RouteResponse::ok(Value::Object(output))),
            Some(Err(message)) => Ok(RouteResponse::new(StatusCode::BAD_REQUEST, json!({ "error": message }))),
            None => Err(HandlerError::internal("route maps to no action")),
        }
    }

    async fn webhook(&self, call: Call, request: WebhookRequest) -> Result<WebhookResponse, HandlerError> {
        let platform = self.platform(&call)?;
        let signature = request.headers.get("stripe-signature");
        let (outcome, touched) =
            webhook::process(&platform, call.tenant_id(), &request.body, signature, Utc::now()).await;
        for member in &touched.members {
            self.caches.invalidate_member(call.tenant_id(), member);
        }
        Ok(match outcome {
            Outcome::Accepted { event_type } => {
                let body = match event_type {
                    Some(t) => json!({ "eventType": t }),
                    None => json!({}),
                };
                WebhookResponse::new(StatusCode::OK, Some(body))
            }
            Outcome::Rejected => WebhookResponse::new(StatusCode::UNAUTHORIZED, Some(json!({ "error": "Rejected" }))),
            Outcome::Unavailable => WebhookResponse::new(StatusCode::SERVICE_UNAVAILABLE, None),
            Outcome::Failed => WebhookResponse::new(StatusCode::INTERNAL_SERVER_ERROR, None),
        })
    }

    async fn lifecycle(&self, call: Call, event: LifecycleEvent) -> Result<(), HandlerError> {
        tracing::info!(event = ?event.event, version = %event.module_version, "lifecycle");
        let tenant = call.tenant_id();
        self.caches.invalidate_tenant_entitlements(tenant);
        self.caches.invalidate_rules(tenant);
        Ok(())
    }
}
