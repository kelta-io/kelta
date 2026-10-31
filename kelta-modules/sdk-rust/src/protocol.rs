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
/// Only ever built from the verified assertion. Absent on flow steps, schedules, webhooks and
/// events, which have no request caller.
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
}

impl Actor {
    /// True when the actor is a portal member (`user_type` is `PORTAL`).
    pub fn is_portal(&self) -> bool {
        self.user_type.as_deref() == Some("PORTAL")
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

/// `TaskHeartbeat`: what `POST /api/modules/self/tasks/{token}/heartbeat` returns.
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
