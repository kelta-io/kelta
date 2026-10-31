//! A builder for the module manifest (`manifestVersion: 2`, `ModuleManifest` in the contract),
//! served at `/.well-known/kelta-module.json`.
//!
//! [`Manifest::build`] checks what the platform's installer would refuse — ids, action keys,
//! timeouts, cache TTLs, route depth, schedules naming undeclared actions — so a bad manifest
//! fails the module's own tests instead of an install.

use std::collections::HashSet;

use serde::Serialize;
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};

/// A manifest the platform would refuse.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("invalid module manifest: {0}")]
pub struct ManifestError(pub String);

fn invalid(message: impl Into<String>) -> ManifestError {
    ManifestError(message.into())
}

/// A tenant collection grant (`permissions.objects[]`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ObjectGrant {
    pub collection: String,
    pub read: bool,
    pub create: bool,
    pub edit: bool,
    pub delete: bool,
}

impl ObjectGrant {
    pub fn new(collection: impl Into<String>) -> Self {
        Self { collection: collection.into(), read: false, create: false, edit: false, delete: false }
    }

    pub fn read(mut self) -> Self {
        self.read = true;
        self
    }

    pub fn create(mut self) -> Self {
        self.create = true;
        self
    }

    pub fn edit(mut self) -> Self {
        self.edit = true;
        self
    }

    pub fn delete(mut self) -> Self {
        self.delete = true;
        self
    }
}

/// `permissions`: what the module's service principal may do, shown to the admin for consent.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct Permissions {
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub objects: Vec<ObjectGrant>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub system: Vec<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub credentials: Vec<String>,
}

impl Permissions {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn object(mut self, grant: ObjectGrant) -> Self {
        self.objects.push(grant);
        self
    }

    pub fn system(mut self, permission: impl Into<String>) -> Self {
        self.system.push(permission.into());
        self
    }

    /// A tenant credential the module may read by name.
    pub fn credential(mut self, name: impl Into<String>) -> Self {
        self.credentials.push(name.into());
        self
    }

    fn is_empty(&self) -> bool {
        self.objects.is_empty() && self.system.is_empty() && self.credentials.is_empty()
    }
}

/// A field of a provisioned collection (a `FIELD` package item).
#[derive(Debug, Clone, PartialEq)]
pub struct FieldSpec {
    name: String,
    display_name: String,
    field_type: String,
    extra: Map<String, Value>,
}

impl FieldSpec {
    /// A field of this `FieldType` (`STRING`, `TEXT`, `INTEGER`, `BOOLEAN`, `DATETIME`, `JSON`, ...).
    pub fn new(name: impl Into<String>, display_name: impl Into<String>, field_type: impl Into<String>) -> Self {
        Self { name: name.into(), display_name: display_name.into(), field_type: field_type.into(), extra: Map::new() }
    }

    pub fn required(self) -> Self {
        self.attr("required", true)
    }

    pub fn indexed(self) -> Self {
        self.attr("indexed", true)
    }

    /// A unique column: a create or update that repeats a stored non-null value is refused
    /// ([`crate::client::ClientError::is_unique_violation`]). Applied only when the install creates
    /// the field: a field that already exists, adopted or kept from an earlier install, is never
    /// changed.
    pub fn unique(self) -> Self {
        self.attr("unique_constraint", true)
    }

    pub fn description(self, description: impl Into<String>) -> Self {
        self.attr("description", description.into())
    }

    /// Any other `field` column, by its snake_case name.
    pub fn attr(mut self, column: impl Into<String>, value: impl Into<Value>) -> Self {
        self.extra.insert(column.into(), value.into());
        self
    }
}

/// A collection the module provisions (a `COLLECTION` package item and its fields).
#[derive(Debug, Clone, PartialEq)]
pub struct CollectionSpec {
    name: String,
    display_name: String,
    description: Option<String>,
    fields: Vec<FieldSpec>,
}

impl CollectionSpec {
    pub fn new(name: impl Into<String>, display_name: impl Into<String>) -> Self {
        Self { name: name.into(), display_name: display_name.into(), description: None, fields: Vec::new() }
    }

    pub fn description(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }

    pub fn field(mut self, field: FieldSpec) -> Self {
        self.fields.push(field);
        self
    }

    /// The collection's name.
    pub fn name(&self) -> &str {
        &self.name
    }
}

/// A flow action (`actions[]`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Action {
    pub key: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u32>,
    /// Makes the action long-running: a call that carries a `task` may answer `202` and complete
    /// it within this many seconds (1-604800, capped by the platform).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_duration_seconds: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_schema: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_schema: Option<Value>,
}

impl Action {
    pub fn new(key: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            key: key.into(),
            name: name.into(),
            description: None,
            category: None,
            timeout_ms: None,
            max_duration_seconds: None,
            input_schema: None,
            output_schema: None,
        }
    }

    pub fn description(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }

    pub fn category(mut self, category: impl Into<String>) -> Self {
        self.category = Some(category.into());
        self
    }

    pub fn timeout_ms(mut self, timeout_ms: u32) -> Self {
        self.timeout_ms = Some(timeout_ms);
        self
    }

    /// Long-running: see [`Action::max_duration_seconds`](Action#structfield.max_duration_seconds).
    pub fn max_duration_seconds(mut self, seconds: u32) -> Self {
        self.max_duration_seconds = Some(seconds);
        self
    }

    pub fn input_schema(mut self, schema: Value) -> Self {
        self.input_schema = Some(schema);
        self
    }

    pub fn output_schema(mut self, schema: Value) -> Self {
        self.output_schema = Some(schema);
        self
    }
}

/// `hooks[].events[]`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum HookEvent {
    #[serde(rename = "beforeCreate")]
    BeforeCreate,
    #[serde(rename = "beforeUpdate")]
    BeforeUpdate,
    #[serde(rename = "beforeDelete")]
    BeforeDelete,
}

/// `hooks[].failurePolicy`: what the platform does when the hook cannot be reached.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum FailurePolicy {
    /// Refuse the write.
    Fail,
    /// Allow the write.
    Ignore,
}

/// A before-save hook (`hooks[]`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Hook {
    pub collection: String,
    pub events: Vec<HookEvent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub order: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_policy: Option<FailurePolicy>,
}

impl Hook {
    /// A hook on `collection` (`*` for every collection).
    pub fn new(collection: impl Into<String>, events: impl IntoIterator<Item = HookEvent>) -> Self {
        Self {
            collection: collection.into(),
            events: events.into_iter().collect(),
            order: None,
            timeout_ms: None,
            failure_policy: None,
        }
    }

    pub fn order(mut self, order: i32) -> Self {
        self.order = Some(order);
        self
    }

    pub fn timeout_ms(mut self, timeout_ms: u32) -> Self {
        self.timeout_ms = Some(timeout_ms);
        self
    }

    pub fn failure_policy(mut self, policy: FailurePolicy) -> Self {
        self.failure_policy = Some(policy);
        self
    }
}

/// A route (`routes[]`), reached at `/api/modules/{moduleId}/x/{path}`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Route {
    pub path: String,
    pub methods: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub permission: Option<String>,
}

impl Route {
    /// A route serving these methods (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`).
    pub fn new(path: impl Into<String>, methods: impl IntoIterator<Item = &'static str>) -> Self {
        Self { path: path.into(), methods: methods.into_iter().map(str::to_string).collect(), permission: None }
    }

    /// A system permission the caller must hold.
    pub fn permission(mut self, permission: impl Into<String>) -> Self {
        self.permission = Some(permission.into());
        self
    }
}

/// The module manifest.
#[derive(Debug, Clone, PartialEq)]
pub struct Manifest {
    id: String,
    name: String,
    version: String,
    description: Option<String>,
    author: Option<String>,
    min_platform_version: Option<String>,
    permissions: Permissions,
    collections: Vec<CollectionSpec>,
    settings: Option<Value>,
    actions: Vec<Action>,
    hooks: Vec<Hook>,
    events: Vec<(String, Vec<&'static str>)>,
    entitlements_cache_ttl: Option<u32>,
    routes: Vec<Route>,
    schedules: Vec<(String, String)>,
    webhook: bool,
    ui: Option<(String, String)>,
}

impl Manifest {
    /// A manifest for module `id` at `version`.
    pub fn new(id: impl Into<String>, name: impl Into<String>, version: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            version: version.into(),
            description: None,
            author: None,
            min_platform_version: None,
            permissions: Permissions::default(),
            collections: Vec::new(),
            settings: None,
            actions: Vec::new(),
            hooks: Vec::new(),
            events: Vec::new(),
            entitlements_cache_ttl: None,
            routes: Vec::new(),
            schedules: Vec::new(),
            webhook: false,
            ui: None,
        }
    }

    pub fn description(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }

    pub fn author(mut self, author: impl Into<String>) -> Self {
        self.author = Some(author.into());
        self
    }

    pub fn min_platform_version(mut self, version: impl Into<String>) -> Self {
        self.min_platform_version = Some(version.into());
        self
    }

    pub fn permissions(mut self, permissions: Permissions) -> Self {
        self.permissions = permissions;
        self
    }

    /// A collection to provision, emitted into `metadata` as a `COLLECTION` item and its `FIELD`s.
    pub fn collection(mut self, collection: CollectionSpec) -> Self {
        self.collections.push(collection);
        self
    }

    /// The JSON Schema of per-install settings.
    pub fn settings(mut self, schema: Value) -> Self {
        self.settings = Some(schema);
        self
    }

    pub fn action(mut self, action: Action) -> Self {
        self.actions.push(action);
        self
    }

    pub fn hook(mut self, hook: Hook) -> Self {
        self.hooks.push(hook);
        self
    }

    /// Record events on `collection` (`created`, `updated`, `deleted`).
    pub fn events(mut self, collection: impl Into<String>, types: impl IntoIterator<Item = &'static str>) -> Self {
        self.events.push((collection.into(), types.into_iter().collect()));
        self
    }

    /// Serves the `entitlements` port; the platform caches answers for `cache_ttl_seconds` (0-300).
    pub fn entitlements_service(mut self, cache_ttl_seconds: u32) -> Self {
        self.entitlements_cache_ttl = Some(cache_ttl_seconds);
        self
    }

    pub fn route(mut self, route: Route) -> Self {
        self.routes.push(route);
        self
    }

    /// Runs `action` on a Spring 6-field cron, in UTC.
    pub fn schedule(mut self, action: impl Into<String>, cron: impl Into<String>) -> Self {
        self.schedules.push((action.into(), cron.into()));
        self
    }

    /// Accepts third-party webhooks at `/api/modules/webhooks/{tenantId}/{moduleId}`.
    pub fn webhook(mut self) -> Self {
        self.webhook = true;
        self
    }

    /// The admin UI bundle served at `bundle_path`; its SHA-256 is pinned from `bundle`.
    pub fn ui_bundle(mut self, bundle_path: impl Into<String>, bundle: &[u8]) -> Self {
        self.ui = Some((bundle_path.into(), hex::encode(Sha256::digest(bundle))));
        self
    }

    /// The manifest JSON, after the checks the installer applies.
    pub fn build(&self) -> Result<Value, ManifestError> {
        self.validate()?;
        let mut out = Map::new();
        out.insert("manifestVersion".into(), json!(2));
        out.insert("protocolVersion".into(), json!(1));
        out.insert("id".into(), json!(self.id));
        out.insert("name".into(), json!(self.name));
        out.insert("version".into(), json!(self.version));
        insert_opt(&mut out, "description", &self.description);
        insert_opt(&mut out, "author", &self.author);
        insert_opt(&mut out, "minPlatformVersion", &self.min_platform_version);
        if !self.permissions.is_empty() {
            out.insert("permissions".into(), to_value(&self.permissions)?);
        }
        if !self.collections.is_empty() {
            out.insert("metadata".into(), self.metadata_package());
        }
        if let Some(settings) = &self.settings {
            out.insert("settings".into(), settings.clone());
        }
        if !self.actions.is_empty() {
            out.insert("actions".into(), to_value(&self.actions)?);
        }
        if !self.hooks.is_empty() {
            out.insert("hooks".into(), to_value(&self.hooks)?);
        }
        if !self.events.is_empty() {
            let events: Vec<Value> =
                self.events.iter().map(|(c, types)| json!({ "collection": c, "types": types })).collect();
            out.insert("events".into(), Value::Array(events));
        }
        if let Some(ttl) = self.entitlements_cache_ttl {
            out.insert("services".into(), json!([{ "port": "entitlements", "cacheTtlSeconds": ttl }]));
        }
        if !self.routes.is_empty() {
            out.insert("routes".into(), to_value(&self.routes)?);
        }
        if !self.schedules.is_empty() {
            let schedules: Vec<Value> =
                self.schedules.iter().map(|(action, cron)| json!({ "action": action, "cron": cron })).collect();
            out.insert("schedules".into(), Value::Array(schedules));
        }
        if self.webhook {
            out.insert("webhook".into(), json!({ "enabled": true }));
        }
        if let Some((path, sha256)) = &self.ui {
            out.insert("ui".into(), json!({ "bundlePath": path, "sha256": sha256 }));
        }
        Ok(Value::Object(out))
    }

    /// The `metadata` package: `COLLECTION` then `FIELD` items, keyed by the `collection` and
    /// `field` table columns as `POST /api/packages/export` writes them.
    fn metadata_package(&self) -> Value {
        let mut items = Vec::new();
        for collection in &self.collections {
            let mut data = Map::new();
            data.insert("name".into(), json!(collection.name));
            data.insert("display_name".into(), json!(collection.display_name));
            data.insert("current_version".into(), json!(1));
            data.insert("active".into(), json!(true));
            insert_opt(&mut data, "description", &collection.description);
            items.push(json!({ "type": "COLLECTION", "data": data }));
        }
        for collection in &self.collections {
            for (order, field) in collection.fields.iter().enumerate() {
                let mut data = Map::new();
                data.insert("collection_name".into(), json!(collection.name));
                data.insert("name".into(), json!(field.name));
                data.insert("display_name".into(), json!(field.display_name));
                data.insert("type".into(), json!(field.field_type));
                data.insert("field_order".into(), json!(order));
                for (k, v) in &field.extra {
                    data.insert(k.clone(), v.clone());
                }
                items.push(json!({ "type": "FIELD", "data": data }));
            }
        }
        json!({ "formatVersion": 2, "items": items })
    }

    fn validate(&self) -> Result<(), ManifestError> {
        if !is_slug(&self.id, 2, 63) {
            return Err(invalid(format!("id '{}' must match ^[a-z][a-z0-9-]{{1,62}}$", self.id)));
        }
        if self.name.trim().is_empty() {
            return Err(invalid("name is required"));
        }
        if !is_semver(&self.version) {
            return Err(invalid(format!("version '{}' is not MAJOR.MINOR.PATCH", self.version)));
        }
        for grant in &self.permissions.objects {
            if grant.collection.trim().is_empty() || grant.collection.contains('*') {
                return Err(invalid("an object grant needs an exact collection name"));
            }
        }
        for name in &self.permissions.credentials {
            let ok = !name.is_empty()
                && name.len() <= 64
                && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | ':' | '-'));
            if !ok {
                return Err(invalid(format!("credential name '{name}' must match ^[A-Za-z0-9_.:-]{{1,64}}$")));
            }
        }
        let mut collections = HashSet::new();
        for collection in &self.collections {
            if !collections.insert(collection.name.as_str()) {
                return Err(invalid(format!("collection '{}' is declared twice", collection.name)));
            }
            let mut fields = HashSet::new();
            for field in &collection.fields {
                if field.name.trim().is_empty() || !fields.insert(field.name.as_str()) {
                    return Err(invalid(format!("collection '{}' has a blank or repeated field", collection.name)));
                }
            }
        }
        let mut keys = HashSet::new();
        for action in &self.actions {
            if !is_slug(&action.key, 1, 63) {
                return Err(invalid(format!("action key '{}' must match ^[a-z][a-z0-9-]{{0,62}}$", action.key)));
            }
            if !keys.insert(action.key.as_str()) {
                return Err(invalid(format!("action '{}' is declared twice", action.key)));
            }
            if action.timeout_ms.is_some_and(|t| !(1..=120_000).contains(&t)) {
                return Err(invalid(format!("action '{}' timeoutMs must be 1-120000", action.key)));
            }
            if action.max_duration_seconds.is_some_and(|s| !(1..=604_800).contains(&s)) {
                return Err(invalid(format!("action '{}' maxDurationSeconds must be 1-604800", action.key)));
            }
        }
        for hook in &self.hooks {
            if hook.collection.trim().is_empty() || hook.events.is_empty() {
                return Err(invalid("a hook needs a collection and at least one event"));
            }
            if hook.timeout_ms.is_some_and(|t| !(100..=5000).contains(&t)) {
                return Err(invalid(format!("hook on '{}' timeoutMs must be 100-5000", hook.collection)));
            }
        }
        for (collection, types) in &self.events {
            if collection.trim().is_empty() || types.is_empty() {
                return Err(invalid("an event subscription needs a collection and at least one type"));
            }
            if let Some(bad) = types.iter().find(|t| !matches!(**t, "created" | "updated" | "deleted")) {
                return Err(invalid(format!("event type '{bad}' is not created, updated or deleted")));
            }
        }
        if self.entitlements_cache_ttl.is_some_and(|ttl| ttl > 300) {
            return Err(invalid("entitlements cacheTtlSeconds must be 0-300"));
        }
        let mut paths = HashSet::new();
        for route in &self.routes {
            let segments: Vec<&str> = route.path.split('/').collect();
            let well_formed = !route.path.starts_with('/')
                && (1..=3).contains(&segments.len())
                && segments.iter().all(|s| !s.is_empty() && *s != "." && *s != "..");
            if !well_formed {
                return Err(invalid(format!("route '{}' must be a relative path of 1-3 segments", route.path)));
            }
            if !paths.insert(route.path.as_str()) {
                return Err(invalid(format!("route '{}' is declared twice", route.path)));
            }
            if route.methods.is_empty()
                || route.methods.iter().any(|m| !matches!(m.as_str(), "GET" | "POST" | "PUT" | "PATCH" | "DELETE"))
            {
                return Err(invalid(format!(
                    "route '{}' needs methods among GET, POST, PUT, PATCH, DELETE",
                    route.path
                )));
            }
        }
        for (action, cron) in &self.schedules {
            if !keys.contains(action.as_str()) {
                return Err(invalid(format!("schedule names undeclared action '{action}'")));
            }
            if cron.split_whitespace().count() != 6 {
                return Err(invalid(format!("schedule cron '{cron}' must have 6 fields")));
            }
        }
        if let Some((path, _)) = &self.ui
            && (!path.starts_with('/') || path.contains(".."))
        {
            return Err(invalid("ui bundlePath must be an absolute path on the module"));
        }
        Ok(())
    }
}

fn insert_opt(map: &mut Map<String, Value>, key: &str, value: &Option<String>) {
    if let Some(value) = value {
        map.insert(key.into(), json!(value));
    }
}

fn to_value<T: Serialize>(value: &T) -> Result<Value, ManifestError> {
    serde_json::to_value(value).map_err(|e| invalid(e.to_string()))
}

/// `^[a-z][a-z0-9-]{min-1,max-1}$`.
fn is_slug(value: &str, min: usize, max: usize) -> bool {
    let mut chars = value.chars();
    (min..=max).contains(&value.len())
        && chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

fn is_semver(value: &str) -> bool {
    let core = value.split(['-', '+']).next().unwrap_or_default();
    let parts: Vec<&str> = core.split('.').collect();
    parts.len() == 3 && parts.iter().all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> Manifest {
        Manifest::new("acme-tools", "Acme tools", "1.2.3")
    }

    #[test]
    fn builds_the_contract_shape() {
        let manifest = base()
            .description("Tools")
            .permissions(Permissions::new().object(ObjectGrant::new("orders").read().edit()).credential("stripe"))
            .collection(
                CollectionSpec::new("acme_items", "Acme items")
                    .field(FieldSpec::new("code", "Code", "STRING").required())
                    .field(FieldSpec::new("count", "Count", "INTEGER")),
            )
            .action(Action::new("ping", "Ping").timeout_ms(5000))
            .action(Action::new("ingest", "Ingest").max_duration_seconds(7200))
            .hook(Hook::new("*", [HookEvent::BeforeCreate]).order(-90).failure_policy(FailurePolicy::Ignore))
            .events("acme_items", ["created", "deleted"])
            .entitlements_service(0)
            .route(Route::new("items/detail", ["GET", "POST"]))
            .schedule("ping", "0 0 * * * *")
            .webhook()
            .ui_bundle("/ui/bundle.js", b"x")
            .build()
            .unwrap();
        assert_eq!(manifest["manifestVersion"], 2);
        assert_eq!(manifest["protocolVersion"], 1);
        assert_eq!(
            manifest["permissions"]["objects"][0],
            json!({"collection":"orders","read":true,"create":false,"edit":true,"delete":false})
        );
        assert_eq!(manifest["permissions"]["credentials"], json!(["stripe"]));
        let items = manifest["metadata"]["items"].as_array().unwrap();
        assert_eq!(items[0]["type"], "COLLECTION");
        assert_eq!(items[0]["data"]["display_name"], "Acme items");
        assert_eq!(
            items[1]["data"],
            json!({"collection_name":"acme_items","name":"code","display_name":"Code","type":"STRING","field_order":0,"required":true})
        );
        assert_eq!(
            manifest["hooks"][0],
            json!({"collection":"*","events":["beforeCreate"],"order":-90,"failurePolicy":"ignore"})
        );
        assert_eq!(manifest["services"], json!([{"port":"entitlements","cacheTtlSeconds":0}]));
        assert_eq!(manifest["routes"][0]["methods"], json!(["GET", "POST"]));
        assert_eq!(manifest["webhook"], json!({"enabled": true}));
        assert_eq!(manifest["ui"]["sha256"], "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881");
        assert!(manifest.get("settings").is_none());
        assert!(manifest["actions"][0].get("maxDurationSeconds").is_none());
        assert_eq!(manifest["actions"][1]["maxDurationSeconds"], 7200);
    }

    #[test]
    fn refuses_what_the_installer_refuses() {
        let refused = |m: Manifest| m.build().is_err();
        assert!(refused(Manifest::new("Acme", "x", "1.0.0")));
        assert!(refused(Manifest::new("a", "x", "1.0.0")));
        assert!(refused(Manifest::new("acme", " ", "1.0.0")));
        assert!(refused(Manifest::new("acme", "x", "1.0")));
        assert!(refused(base().action(Action::new("Ping", "Ping"))));
        assert!(refused(base().action(Action::new("ping", "Ping")).action(Action::new("ping", "Again"))));
        assert!(refused(base().action(Action::new("ping", "Ping").timeout_ms(120_001))));
        assert!(refused(base().action(Action::new("ping", "Ping").max_duration_seconds(0))));
        assert!(refused(base().action(Action::new("ping", "Ping").max_duration_seconds(604_801))));
        assert!(!refused(base().action(Action::new("ping", "Ping").max_duration_seconds(604_800))));
        assert!(refused(base().hook(Hook::new("*", [HookEvent::BeforeCreate]).timeout_ms(50))));
        assert!(refused(base().hook(Hook::new("*", []))));
        assert!(refused(base().events("x", ["touched"])));
        assert!(refused(base().entitlements_service(301)));
        assert!(refused(base().route(Route::new("/plans", ["GET"]))));
        assert!(refused(base().route(Route::new("a/b/c/d", ["GET"]))));
        assert!(refused(base().route(Route::new("a//b", ["GET"]))));
        assert!(refused(base().route(Route::new("plans", ["HEAD"]))));
        assert!(refused(base().schedule("missing", "0 0 * * * *")));
        assert!(refused(base().action(Action::new("ping", "Ping")).schedule("ping", "0 * * * *")));
        assert!(refused(base().permissions(Permissions::new().object(ObjectGrant::new("*").read()))));
        assert!(refused(base().permissions(Permissions::new().credential("bad name"))));
        assert!(refused(base().ui_bundle("ui/bundle.js", b"x")));
        assert!(refused(
            base().collection(CollectionSpec::new("c", "C")).collection(CollectionSpec::new("c", "C again"))
        ));
        assert!(refused(
            base().collection(
                CollectionSpec::new("c", "C")
                    .field(FieldSpec::new("f", "F", "STRING"))
                    .field(FieldSpec::new("f", "F", "STRING"))
            )
        ));
        assert!(!refused(Manifest::new("ab", "x", "1.0.0-rc.1")));
    }
}
