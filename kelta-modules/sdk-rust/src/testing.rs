//! Test support for module authors (feature `testing`): sign assertions the way the platform does,
//! serve a JWKS from memory, and stand in for the platform API.
//!
//! Never enable this feature outside `[dev-dependencies]`.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use jsonwebtoken::{Algorithm, EncodingKey, Header};
use p256::ecdsa::SigningKey;
use p256::elliptic_curve::Generate;
use p256::pkcs8::EncodePrivateKey;
use serde_json::{Map, Value, json};

use crate::assertion::{AssertionVerifier, JwksSource, VerifierConfig, body_sha256};
use crate::client::{ClientError, Page, Platform, Query, Resource};
use crate::protocol::ModuleCredential;

/// The issuer test assertions carry.
pub const ISSUER: &str = "https://platform.example.test/api/modules";
/// The module id test assertions are addressed to.
pub const MODULE_ID: &str = "acme-billing";
/// The base URL test assertions are signed for.
pub const BASE_URL: &str = "https://module.example.test";
/// The tenant test assertions name.
pub const TENANT_ID: &str = "00000000-0000-0000-0000-00000000000a";

/// A platform signing key (P-256) and its public JWK.
pub struct PlatformKey {
    pub kid: String,
    encoding: EncodingKey,
    jwk: Value,
}

impl PlatformKey {
    /// A fresh random key named `kid`.
    pub fn generate(kid: &str) -> Self {
        let signing = SigningKey::generate();
        let der = signing.to_pkcs8_der().expect("a P-256 key encodes as PKCS#8");
        let encoding = EncodingKey::from_ec_der(der.as_bytes());
        let point = signing.verifying_key().to_sec1_point(false);
        let jwk = json!({
            "kty": "EC", "crv": "P-256", "use": "sig", "alg": "ES256", "kid": kid,
            "x": URL_SAFE_NO_PAD.encode(point.x().expect("uncompressed point")),
            "y": URL_SAFE_NO_PAD.encode(point.y().expect("uncompressed point")),
        });
        Self { kid: kid.to_string(), encoding, jwk }
    }

    /// The public JWK.
    pub fn jwk(&self) -> Value {
        self.jwk.clone()
    }

    /// Signs `claims` ES256, naming `kid` in the header (or none).
    pub fn sign(&self, claims: &Value, kid: Option<&str>) -> String {
        let mut header = Header::new(Algorithm::ES256);
        header.kid = kid.map(str::to_string);
        jsonwebtoken::encode(&header, claims, &self.encoding).expect("signing succeeds")
    }

    /// Signs `claims` naming this key's own `kid`.
    pub fn sign_own(&self, claims: &Value) -> String {
        self.sign(claims, Some(&self.kid))
    }
}

/// An in-memory JWKS that counts fetches and can be rotated or broken.
pub struct TestJwks {
    pub document: Mutex<Result<String, String>>,
    fetches: AtomicUsize,
}

impl TestJwks {
    /// A JWKS holding these keys.
    pub fn with_keys(keys: &[&PlatformKey]) -> Arc<Self> {
        let jwks = Arc::new(Self { document: Mutex::new(Ok(String::new())), fetches: AtomicUsize::new(0) });
        jwks.set_keys(keys);
        jwks
    }

    /// A JWKS that cannot be fetched.
    pub fn failing() -> Arc<Self> {
        Arc::new(Self { document: Mutex::new(Err("connection refused".into())), fetches: AtomicUsize::new(0) })
    }

    /// Replaces the served keys.
    pub fn set_keys(&self, keys: &[&PlatformKey]) {
        let doc = json!({ "keys": keys.iter().map(|k| k.jwk()).collect::<Vec<_>>() }).to_string();
        *self.document.lock().unwrap_or_else(|p| p.into_inner()) = Ok(doc);
    }

    /// How many times the verifier fetched.
    pub fn fetches(&self) -> usize {
        self.fetches.load(Ordering::SeqCst)
    }
}

impl JwksSource for TestJwks {
    fn fetch(&self) -> Pin<Box<dyn Future<Output = Result<String, String>> + Send + '_>> {
        self.fetches.fetch_add(1, Ordering::SeqCst);
        let doc = self.document.lock().unwrap_or_else(|p| p.into_inner()).clone();
        Box::pin(async move { doc })
    }
}

/// The verifier configuration test assertions satisfy.
pub fn config() -> VerifierConfig {
    VerifierConfig::new(ISSUER, MODULE_ID, vec![BASE_URL.to_string()])
}

/// A verifier over `jwks` with [`config`].
pub fn verifier(jwks: Arc<TestJwks>) -> AssertionVerifier {
    verifier_with(config(), jwks)
}

/// A verifier over `jwks` with `config`.
pub fn verifier_with(config: VerifierConfig, jwks: Arc<TestJwks>) -> AssertionVerifier {
    AssertionVerifier::with_source(config, jwks).expect("a valid test configuration")
}

/// Seconds since the epoch.
pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or_default()
}

static JTI: AtomicUsize = AtomicUsize::new(0);

/// Claims for `method` `path_and_query` with `body`, shaped like `ModuleAssertionSigner` signs
/// them, with a fresh `jti` and a portal actor `user-1`. Adjust fields with ordinary JSON edits.
pub fn claims(method: &str, path_and_query: &str, body: &[u8]) -> Value {
    let iat = now();
    json!({
        "iss": ISSUER,
        "aud": MODULE_ID,
        "sub": "install-1",
        "tenant_id": TENANT_ID,
        "tenant_slug": "acme",
        "act": { "sub": "user-1", "user_type": "PORTAL" },
        "htm": method,
        "htu": format!("{BASE_URL}{path_and_query}"),
        "body_sha256": body_sha256(body),
        "iat": iat,
        "exp": iat + 60,
        "jti": format!("jti-{}-{}", std::process::id(), JTI.fetch_add(1, Ordering::SeqCst)),
    })
}

/// `Bearer <token>`.
pub fn bearer(token: &str) -> String {
    format!("Bearer {token}")
}

#[derive(Default)]
struct MemoryState {
    collections: HashMap<String, Vec<Resource>>,
    credentials: HashMap<String, ModuleCredential>,
    refused: HashSet<String>,
    unique: HashSet<(String, String)>,
    unavailable: bool,
    next_id: u64,
    writes: Vec<String>,
}

/// An in-memory platform: JSON:API collections with `eq`/`neq`/`lt`/`lte`/`gt`/`gte`/`isnull`
/// filters, `sort`, paging and `totalCount`, plus credentials. Clones share state.
#[derive(Clone, Default)]
pub struct MemoryPlatform {
    state: Arc<Mutex<MemoryState>>,
}

impl MemoryPlatform {
    pub fn new() -> Self {
        Self::default()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, MemoryState> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Adds a record and returns its id.
    pub fn insert(&self, collection: &str, attributes: Value) -> String {
        let attributes = match attributes {
            Value::Object(map) => map,
            other => panic!("attributes must be an object, got {other}"),
        };
        let mut state = self.lock();
        state.next_id += 1;
        let id = format!("rec-{}", state.next_id);
        state.collections.entry(collection.to_string()).or_default().push(Resource { id: id.clone(), attributes });
        id
    }

    /// Every record of `collection`.
    pub fn records(&self, collection: &str) -> Vec<Resource> {
        self.lock().collections.get(collection).cloned().unwrap_or_default()
    }

    /// One record by id.
    pub fn record(&self, collection: &str, id: &str) -> Option<Resource> {
        self.records(collection).into_iter().find(|r| r.id == id)
    }

    /// Serves `credential` under its name.
    pub fn set_credential(&self, credential: ModuleCredential) {
        self.lock().credentials.insert(credential.name.clone(), credential);
    }

    /// Answers `403` for every call on `collection`, like a collection the principal may not read.
    pub fn refuse(&self, collection: &str) {
        self.lock().refused.insert(collection.to_string());
    }

    /// Enforces a unique `field` on `collection`, as a field with `unique_constraint` does: a
    /// create repeating a stored non-null value answers `400` `unique`, as the platform's
    /// validation does.
    pub fn unique(&self, collection: &str, field: &str) {
        self.lock().unique.insert((collection.to_string(), field.to_string()));
    }

    /// Answers `503` to every call while `true`.
    pub fn set_unavailable(&self, unavailable: bool) {
        self.lock().unavailable = unavailable;
    }

    /// Every write, as `create <collection>` or `update <collection>/<id>`.
    pub fn writes(&self) -> Vec<String> {
        self.lock().writes.clone()
    }

    fn check_unique(&self, collection: &str, attributes: &Map<String, Value>) -> Result<(), ClientError> {
        let state = self.lock();
        let rows = state.collections.get(collection).map(Vec::as_slice).unwrap_or_default();
        for (unique_collection, field) in &state.unique {
            let Some(value) = attributes.get(field).filter(|v| !v.is_null()) else {
                continue;
            };
            if unique_collection == collection && rows.iter().any(|r| r.attributes.get(field) == Some(value)) {
                // What the platform's validation answers for a value it finds stored.
                return Err(ClientError::Status {
                    status: 400,
                    code: Some("unique".into()),
                    message: format!("{collection}.{field} must be unique"),
                });
            }
        }
        Ok(())
    }

    fn check(&self, collection: &str) -> Result<(), ClientError> {
        let state = self.lock();
        if state.unavailable {
            return Err(ClientError::Status { status: 503, code: None, message: "unavailable".into() });
        }
        if state.refused.contains(collection) {
            return Err(ClientError::Status {
                status: 403,
                code: Some("FORBIDDEN".into()),
                message: "forbidden".into(),
            });
        }
        Ok(())
    }

    fn list_now(&self, collection: &str, query: &Query) -> Result<Page, ClientError> {
        self.check(collection)?;
        let mut rows: Vec<Resource> = self
            .records(collection)
            .into_iter()
            .filter(|r| query.filters().iter().all(|(field, op, value)| matches(r.attributes.get(field), op, value)))
            .collect();
        if let Some(sort) = query.sort_spec() {
            for key in sort.split(',').rev() {
                let (field, descending) = match key.strip_prefix('-') {
                    Some(field) => (field, true),
                    None => (key, false),
                };
                rows.sort_by(|a, b| {
                    let order = compare(a.attributes.get(field), b.attributes.get(field));
                    if descending { order.reverse() } else { order }
                });
            }
        }
        let total = rows.len() as u64;
        let size = query.size().unwrap_or(20).clamp(1, 200) as usize;
        let number = query.number().unwrap_or(1).max(1) as usize;
        let data = rows.into_iter().skip((number - 1) * size).take(size).collect();
        Ok(Page { data, total_count: Some(total) })
    }
}

impl Platform for MemoryPlatform {
    fn list(&self, collection: &str, query: &Query) -> impl Future<Output = Result<Page, ClientError>> + Send {
        let result = self.list_now(collection, query);
        async move { result }
    }

    fn get(&self, collection: &str, id: &str) -> impl Future<Output = Result<Option<Resource>, ClientError>> + Send {
        let result = self.check(collection).map(|()| self.record(collection, id));
        async move { result }
    }

    fn create(
        &self,
        collection: &str,
        attributes: Map<String, Value>,
    ) -> impl Future<Output = Result<Resource, ClientError>> + Send {
        let result = self.check(collection).and_then(|()| self.check_unique(collection, &attributes)).map(|()| {
            let id = self.insert(collection, Value::Object(attributes.clone()));
            self.lock().writes.push(format!("create {collection}"));
            Resource { id, attributes }
        });
        async move { result }
    }

    fn update(
        &self,
        collection: &str,
        id: &str,
        attributes: Map<String, Value>,
    ) -> impl Future<Output = Result<Resource, ClientError>> + Send {
        let result = self.check(collection).and_then(|()| {
            let mut state = self.lock();
            state.writes.push(format!("update {collection}/{id}"));
            let row = state
                .collections
                .get_mut(collection)
                .and_then(|rows| rows.iter_mut().find(|r| r.id == id))
                .ok_or_else(|| ClientError::Status {
                    status: 404,
                    code: Some("NOT_FOUND".into()),
                    message: "not found".into(),
                })?;
            for (k, v) in attributes {
                row.attributes.insert(k, v);
            }
            Ok(row.clone())
        });
        async move { result }
    }

    fn credential(
        &self,
        name: &str,
        _purpose: Option<&str>,
    ) -> impl Future<Output = Result<ModuleCredential, ClientError>> + Send {
        let result = {
            let state = self.lock();
            if state.unavailable {
                Err(ClientError::Status {
                    status: 503,
                    code: Some("CREDENTIALS_UNAVAILABLE".into()),
                    message: "unavailable".into(),
                })
            } else {
                state.credentials.get(name).cloned().ok_or_else(|| ClientError::Status {
                    status: 404,
                    code: Some("CREDENTIAL_NOT_FOUND".into()),
                    message: "not found".into(),
                })
            }
        };
        async move { result }
    }
}

fn matches(actual: Option<&Value>, op: &str, expected: &str) -> bool {
    let actual = actual.filter(|v| !v.is_null());
    match op {
        "isnull" => actual.is_none() == (expected == "true"),
        "eq" => actual.is_some_and(|v| scalar(v) == expected),
        "neq" => actual.is_none_or(|v| scalar(v) != expected),
        "lt" | "lte" | "gt" | "gte" => actual.is_some_and(|v| {
            let order = compare(Some(v), Some(&typed_like(v, expected)));
            match op {
                "lt" => order.is_lt(),
                "lte" => order.is_le(),
                "gt" => order.is_gt(),
                _ => order.is_ge(),
            }
        }),
        other => panic!("MemoryPlatform does not support filter op '{other}'"),
    }
}

fn scalar(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

fn typed_like(template: &Value, text: &str) -> Value {
    match template {
        Value::Number(_) => text.parse::<f64>().map(|n| json!(n)).unwrap_or(Value::String(text.into())),
        _ => Value::String(text.into()),
    }
}

fn compare(a: Option<&Value>, b: Option<&Value>) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    // Postgres orders NULLs last ascending (first descending); so does this.
    match (a.filter(|v| !v.is_null()), b.filter(|v| !v.is_null())) {
        (None, None) => Ordering::Equal,
        (None, Some(_)) => Ordering::Greater,
        (Some(_), None) => Ordering::Less,
        (Some(Value::Number(x)), Some(Value::Number(y))) => {
            x.as_f64().unwrap_or_default().partial_cmp(&y.as_f64().unwrap_or_default()).unwrap_or(Ordering::Equal)
        }
        (Some(x), Some(y)) => scalar(x).cmp(&scalar(y)),
    }
}
