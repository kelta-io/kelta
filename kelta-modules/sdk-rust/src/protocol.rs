//! Wire types of module protocol v1, as `docs/module-protocol/openapi.yaml` defines them.
//!
//! Field names are the contract's (camelCase). Optional fields are `Option` and default when
//! absent, so a platform that adds a field never breaks a module built against an older SDK.

use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// A record's attributes, keyed by field name (`Record` in the contract). Server-only fields are
/// never included; fields the module's profile may not read are removed or masked.
pub type Record = Map<String, Value>;

/// The install's settings (`Settings`). Never contains secrets.
pub type Settings = Map<String, Value>;

/// The user whose action caused a call (`Actor`, the RFC 8693 `act` claim).
///
/// Only ever built from the verified assertion. On a route, hook or entitlements lookup it is the
/// request's caller. On a flow step it is the run's user, and [`source`](Self::source) says how the
/// platform chose it. Absent on module schedules, webhooks, events and lifecycle calls.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Actor {
    /// The user id.
    pub sub: String,
    /// `INTERNAL` or `PORTAL`.
    #[serde(default)]
    pub user_type: Option<String>,
    /// The caller's profile; sent on routes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile_id: Option<String>,
    /// How a flow step's actor was chosen (`act.source`). The platform sends none for a route,
    /// hook or entitlements lookup, where the actor is the request's caller; on an action call,
    /// `None` is not known to be a member (see [`is_initiator`](Self::is_initiator)).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<ActorSource>,
}

impl Actor {
    /// True when the actor is a portal member (`user_type` is `PORTAL`).
    pub fn is_portal(&self) -> bool {
        self.user_type.as_deref() == Some("PORTAL")
    }

    /// True when the actor is a flow run's fallback identity rather than the user its trigger
    /// named: the flow's run-as user or owner, sent because the trigger named no user (a
    /// schedule, a webhook, a record that names no user). A source this SDK does not know counts
    /// as a fallback, so an older module fails closed. False for an actor with no source.
    pub fn is_fallback(&self) -> bool {
        self.source.as_ref().is_some_and(ActorSource::is_fallback)
    }

    /// True only when a flow step's actor is its run's [`Initiator`](ActorSource::Initiator).
    /// False for a fallback, for a source this SDK does not know, and for an actor with no
    /// source.
    ///
    /// A module **action** that acts for "the calling member" (a checkout, a billing portal)
    /// should require this rather than `!is_fallback()`: an action call is a flow step, and one
    /// whose actor carries no source came from a platform that does not say how it chose the
    /// actor, so it is not known to be a member. A route's caller carries no source by design:
    /// check routes with `!is_fallback()`.
    ///
    /// For a record-triggered run the initiator is the record's creator, not necessarily the user
    /// who changed it (see [`ActorSource::Initiator`]).
    pub fn is_initiator(&self) -> bool {
        matches!(self.source, Some(ActorSource::Initiator))
    }
}

/// How the platform chose a flow step's actor (`act.source`).
///
/// An open set: unknown values are kept as [`Other`](Self::Other), so a newer platform never
/// breaks an older module; [`is_fallback`](Self::is_fallback) treats them as fallbacks.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum ActorSource {
    /// The user the run's trigger names: a manual or API run's caller, or for a record-triggered
    /// run the record's **creator** (`createdBy`, else `updatedBy`). That is not necessarily the
    /// user who changed the record, and a flow's own record write can set `createdBy`, so read it
    /// as "the record's author" (the platform's `.claude/docs/concerns.md` tracks the fix). A
    /// retry re-runs as the original run's initiator, whoever asked for it.
    Initiator,
    /// The flow's configured run-as user: the trigger named no user.
    RunAs,
    /// The flow's owner: the trigger named no user and the flow names no run-as user.
    Owner,
    /// A value this SDK does not know, as sent.
    #[serde(untagged)]
    Other(String),
}

impl ActorSource {
    /// True for every source but [`Initiator`](Self::Initiator): the run-as user, the owner, and
    /// any value this SDK does not know.
    pub fn is_fallback(&self) -> bool {
        !matches!(self, Self::Initiator)
    }
}

/// `ActionRequest`: the body of `POST /kelta/v1/actions/{key}`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionRequest {
    pub tenant_id: String,
    /// The step's resolved data: its `input` object when it has one, otherwise the whole data.
    #[serde(default)]
    pub input: Map<String, Value>,
    /// The step's static configuration, `{}` when it has none.
    #[serde(default)]
    pub config: Map<String, Value>,
    #[serde(default)]
    pub collection: Option<String>,
    #[serde(default)]
    pub record_id: Option<String>,
    #[serde(default)]
    pub record: Option<Record>,
    #[serde(default)]
    pub previous_record: Option<Record>,
    #[serde(default)]
    pub changed_fields: Vec<String>,
    #[serde(default)]
    pub execution: Option<Execution>,
    /// Present only for a long-running action (`maxDurationSeconds`) on a step that can wait:
    /// answer [`ActionResponse::accepted`] and complete it later with
    /// `PlatformClient::complete_task`.
    #[serde(default)]
    pub task: Option<ActionTask>,
    #[serde(default)]
    pub settings: Option<Settings>,
}

/// `ActionTask`: the task of a long-running action's call.
///
/// `Debug` never prints the token.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionTask {
    /// Single use, and a secret: keep it only until the task is complete, never log it.
    pub token: String,
    /// When the step stops waiting and fails with `ModuleTimeoutException` (RFC 3339).
    pub expires_at: String,
}

impl fmt::Debug for ActionTask {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ActionTask").field("expires_at", &self.expires_at).finish_non_exhaustive()
    }
}

/// `ActionRequest.execution`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Execution {
    #[serde(default)]
    pub execution_id: Option<String>,
    #[serde(default)]
    pub flow_id: Option<String>,
    #[serde(default)]
    pub step_name: Option<String>,
    #[serde(default)]
    pub attempt: Option<u32>,
    #[serde(default)]
    pub trigger_depth: Option<u32>,
}

/// `ModuleError`: a business failure's code and message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModuleError {
    pub code: String,
    pub message: String,
}

/// `ActionResponse`. A business failure is `200` with `success: false`; the platform turns it into
/// the flow failure `<code>: <message>`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ActionResponse {
    pub success: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<Map<String, Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ModuleError>,
    /// Set by [`ActionResponse::accepted`]; never on the wire.
    #[serde(skip)]
    accepted: bool,
}

impl ActionResponse {
    /// Success with an output object (at most 1000 top-level keys).
    pub fn success(output: Map<String, Value>) -> Self {
        Self { success: true, output: Some(output), error: None, accepted: false }
    }

    /// A business failure.
    pub fn failure(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            success: false,
            output: None,
            error: Some(ModuleError { code: code.into(), message: message.into() }),
            accepted: false,
        }
    }

    /// `202 Accepted`: the module took on the request's `task` and completes it later with
    /// `PlatformClient::complete_task`. Only for a request that carries `task`; the router answers
    /// `500` otherwise.
    pub fn accepted() -> Self {
        Self { success: true, output: None, error: None, accepted: true }
    }

    /// True for [`ActionResponse::accepted`].
    pub fn is_accepted(&self) -> bool {
        self.accepted
    }
}

/// `HookRequest.operation`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Operation {
    Create,
    Update,
    Delete,
}

/// `HookRequest`: the body of `POST /kelta/v1/hooks`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HookRequest {
    pub tenant_id: String,
    pub collection: String,
    pub operation: Operation,
    /// Null on create.
    #[serde(default)]
    pub record_id: Option<String>,
    /// Absent on delete.
    #[serde(default)]
    pub record: Option<Record>,
    #[serde(default)]
    pub previous_record: Option<Record>,
    #[serde(default)]
    pub changed_fields: Vec<String>,
    #[serde(default)]
    pub settings: Option<Settings>,
}

/// `ValidationError`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ValidationError {
    /// Field name, or `None` for a record-level error.
    pub field: Option<String>,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

/// `HookResponse`: the hook's decision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HookResponse {
    pub allowed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub field_updates: Option<Map<String, Value>>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<ValidationError>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub conflict: Option<ValidationError>,
}

impl HookResponse {
    /// Allow the write unchanged.
    pub fn allow() -> Self {
        Self { allowed: true, field_updates: None, errors: Vec::new(), conflict: None }
    }

    /// Reject the write with a `400` and these errors.
    pub fn reject(errors: Vec<ValidationError>) -> Self {
        Self { allowed: false, field_updates: None, errors, conflict: None }
    }
}

/// `RecordEvent.type`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EventType {
    Created,
    Updated,
    Deleted,
}

/// `RecordEvent`: the body of `POST /kelta/v1/events`. At least once, unordered: dedupe on
/// `event_id`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordEvent {
    pub event_id: String,
    #[serde(rename = "type")]
    pub event_type: EventType,
    pub tenant_id: String,
    pub collection: String,
    pub record_id: String,
    pub timestamp: String,
    #[serde(default)]
    pub data: Option<Record>,
    #[serde(default)]
    pub previous_data: Option<Record>,
    #[serde(default)]
    pub changed_fields: Vec<String>,
    /// True when the event was too large to carry `data`: re-read the record by id.
    #[serde(default)]
    pub payload_omitted: bool,
}

/// The body of `POST /kelta/v1/services/entitlements/resolve`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EntitlementsRequest {
    pub user_id: String,
    #[serde(default)]
    pub settings: Option<Settings>,
}

/// `MemberEntitlements`. An unknown member is `plan_code: None` and empty `values`, never an error.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemberEntitlements {
    pub plan_code: Option<String>,
    pub subscription_status: Option<String>,
    pub values: Map<String, Value>,
}

/// `LifecycleEvent.event`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LifecycleEventKind {
    Installed,
    Upgraded,
    Enabled,
    Disabled,
    Uninstalled,
}

/// `LifecycleEvent`: the body of `POST /kelta/v1/lifecycle`. Handling must be idempotent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LifecycleEvent {
    pub event: LifecycleEventKind,
    pub tenant_id: String,
    pub module_version: String,
    #[serde(default)]
    pub from_version: Option<String>,
    #[serde(default)]
    pub purge_data: Option<bool>,
    #[serde(default)]
    pub settings: Option<Settings>,
}

/// `Health.status`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum HealthStatus {
    Ok,
    Degraded,
}

/// `Health`: the answer to `GET /kelta/v1/health`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Health {
    pub status: HealthStatus,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub details: Option<Map<String, Value>>,
}

/// `TaskHeartbeat`: what `POST /api/modules/self/tasks/heartbeat` returns.
///
/// `Debug` never prints the access token.
#[derive(Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskHeartbeat {
    /// A service-principal token valid at least 10 more minutes; use it for the rest of the work.
    pub access_token: String,
    pub access_token_expires_at: String,
    /// The task's expiry, unchanged by the heartbeat.
    pub task_expires_at: String,
}

impl fmt::Debug for TaskHeartbeat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TaskHeartbeat")
            .field("access_token_expires_at", &self.access_token_expires_at)
            .field("task_expires_at", &self.task_expires_at)
            .finish_non_exhaustive()
    }
}

/// `ModuleCredential`: what `GET /api/modules/self/credentials/{name}` returns.
///
/// `Debug` never prints `secrets`.
#[derive(Clone, PartialEq, Deserialize)]
pub struct ModuleCredential {
    pub name: String,
    #[serde(rename = "type")]
    pub credential_type: String,
    pub secrets: Map<String, Value>,
    pub metadata: Map<String, Value>,
}

impl ModuleCredential {
    /// A secret field as a non-blank string.
    pub fn secret(&self, key: &str) -> Option<&str> {
        non_blank(self.secrets.get(key))
    }

    /// A metadata field.
    pub fn metadata(&self, key: &str) -> Option<&Value> {
        self.metadata.get(key)
    }

    /// A list-like metadata setting, read from either a JSON array or a comma-separated string, as
    /// the contract asks readers to (`allowedReturnOrigins`). Blank entries are dropped.
    pub fn metadata_list(&self, key: &str) -> Vec<String> {
        match self.metadata.get(key) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|item| match item {
                    Value::Null => None,
                    Value::String(s) => Some(s.trim().to_string()),
                    other => Some(other.to_string()),
                })
                .filter(|s| !s.is_empty())
                .collect(),
            Some(Value::String(s)) => {
                s.split(',').map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect()
            }
            _ => Vec::new(),
        }
    }
}

impl std::fmt::Debug for ModuleCredential {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ModuleCredential")
            .field("name", &self.name)
            .field("type", &self.credential_type)
            .field("secrets", &format_args!("<{} redacted>", self.secrets.len()))
            .field("metadata", &self.metadata)
            .finish()
    }
}

fn non_blank(value: Option<&Value>) -> Option<&str> {
    match value {
        Some(Value::String(s)) if !s.trim().is_empty() => Some(s.as_str()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn action_request_reads_the_contract_shape() {
        let request: ActionRequest = serde_json::from_value(json!({
            "tenantId": "t1",
            "input": { "greeting": "hi" },
            "config": {},
            "collection": null,
            "recordId": null,
            "record": null,
            "previousRecord": null,
            "changedFields": [],
            "execution": { "executionId": "e1", "flowId": "f1", "stepName": "Ping", "attempt": 1, "triggerDepth": 0 }
        }))
        .unwrap();
        assert_eq!(request.tenant_id, "t1");
        assert_eq!(request.input["greeting"], "hi");
        assert_eq!(request.execution.unwrap().step_name.as_deref(), Some("Ping"));
        assert!(request.task.is_none());
    }

    #[test]
    fn a_long_running_call_carries_its_task_and_debug_hides_the_token() {
        let request: ActionRequest = serde_json::from_value(json!({
            "tenantId": "t1",
            "input": {},
            "task": { "token": "kmt_secret", "expiresAt": "2026-11-01T12:00:00Z" }
        }))
        .unwrap();
        let task = request.task.unwrap();
        assert_eq!(task.token, "kmt_secret");
        assert_eq!(task.expires_at, "2026-11-01T12:00:00Z");
        assert!(!format!("{task:?}").contains("kmt_secret"));

        let accepted = ActionResponse::accepted();
        assert!(accepted.is_accepted());
        assert!(!ActionResponse::success(Map::new()).is_accepted());
        assert_eq!(serde_json::to_value(&accepted).unwrap(), json!({ "success": true }));

        let heartbeat: TaskHeartbeat = serde_json::from_value(json!({
            "accessToken": "fresh", "accessTokenExpiresAt": "2026-11-01T12:15:00Z",
            "taskExpiresAt": "2026-11-01T14:00:00Z"
        }))
        .unwrap();
        assert_eq!(heartbeat.access_token, "fresh");
        assert!(!format!("{heartbeat:?}").contains("fresh"));
    }

    #[test]
    fn responses_omit_absent_optional_fields() {
        let allowed = serde_json::to_value(HookResponse::allow()).unwrap();
        assert_eq!(allowed, json!({ "allowed": true }));
        let failed = serde_json::to_value(ActionResponse::failure("Code", "message")).unwrap();
        assert_eq!(failed, json!({ "success": false, "error": { "code": "Code", "message": "message" } }));
        let rejected = serde_json::to_value(HookResponse::reject(vec![ValidationError {
            field: None,
            message: "no".into(),
            code: None,
        }]))
        .unwrap();
        assert_eq!(rejected, json!({ "allowed": false, "errors": [ { "field": null, "message": "no" } ] }));
    }

    #[test]
    fn record_event_and_lifecycle_parse() {
        let event: RecordEvent = serde_json::from_value(json!({
            "eventId": "ev1", "type": "updated", "tenantId": "t1", "collection": "c",
            "recordId": "r1", "timestamp": "2026-10-31T00:00:00Z", "data": { "a": 1 },
            "previousData": null, "changedFields": ["a"]
        }))
        .unwrap();
        assert_eq!(event.event_type, EventType::Updated);
        assert!(!event.payload_omitted);
        let lifecycle: LifecycleEvent = serde_json::from_value(json!({
            "event": "upgraded", "tenantId": "t1", "moduleVersion": "2.0.0", "fromVersion": "1.0.0"
        }))
        .unwrap();
        assert_eq!(lifecycle.event, LifecycleEventKind::Upgraded);
    }

    #[test]
    fn an_actor_without_a_source_is_the_caller() {
        let actor: Actor =
            serde_json::from_value(json!({ "sub": "u1", "user_type": "PORTAL", "profile_id": "p1" })).unwrap();
        assert_eq!(actor.source, None);
        assert!(!actor.is_fallback());
        assert!(!actor.is_initiator(), "no source is not known to be an initiator");
        assert!(actor.is_portal());
        assert_eq!(
            serde_json::to_value(&actor).unwrap(),
            json!({ "sub": "u1", "user_type": "PORTAL", "profile_id": "p1" })
        );
    }

    #[test]
    fn a_flow_actor_carries_its_source_and_only_an_initiator_is_not_a_fallback() {
        let read = |source: &str| -> Actor {
            serde_json::from_value(json!({ "sub": "u1", "user_type": "PORTAL", "source": source })).unwrap()
        };
        assert_eq!(read("initiator").source, Some(ActorSource::Initiator));
        assert_eq!(read("run_as").source, Some(ActorSource::RunAs));
        assert_eq!(read("owner").source, Some(ActorSource::Owner));
        assert!(!read("initiator").is_fallback());
        assert!(read("run_as").is_fallback());
        assert!(read("owner").is_fallback());
        assert!(read("initiator").is_initiator());
        assert!(!read("run_as").is_initiator());
        assert!(!read("owner").is_initiator());
        for source in ["initiator", "run_as", "owner"] {
            assert_eq!(serde_json::to_value(read(source)).unwrap()["source"], source);
        }
    }

    #[test]
    fn an_unknown_source_is_kept_and_fails_closed() {
        let actor: Actor = serde_json::from_value(json!({ "sub": "u1", "source": "delegate" })).unwrap();
        assert_eq!(actor.source, Some(ActorSource::Other("delegate".into())));
        assert!(actor.is_fallback());
        assert!(!actor.is_initiator());
        assert_eq!(serde_json::to_value(&actor).unwrap()["source"], "delegate");
    }

    #[test]
    fn credential_lists_accept_arrays_and_comma_strings_and_redact_secrets() {
        let credential: ModuleCredential = serde_json::from_value(json!({
            "name": "stripe", "type": "stripe",
            "secrets": { "secretKey": "sk_test_placeholder" },
            "metadata": { "a": "https://a.test, https://b.test,", "b": ["https://c.test", " ", null] }
        }))
        .unwrap();
        assert_eq!(credential.metadata_list("a"), vec!["https://a.test", "https://b.test"]);
        assert_eq!(credential.metadata_list("b"), vec!["https://c.test"]);
        assert!(credential.metadata_list("missing").is_empty());
        assert_eq!(credential.secret("secretKey"), Some("sk_test_placeholder"));
        assert!(!format!("{credential:?}").contains("sk_test_placeholder"));
    }
}
