//! A client for calling the platform back during a request, with the install's
//! `Kelta-Access-Token` (spec §3.4).
//!
//! The token is short-lived and carries only what the admin approved at install. Get a client from
//! [`crate::Call::platform`] for each request; never store the token or the client beyond it.

use std::fmt;
use std::future::Future;
use std::time::Duration;

use reqwest::{Method, StatusCode};
use serde_json::{Map, Value, json};

use crate::protocol::ModuleCredential;

/// The longest the client waits for the platform on one call.
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(10);

/// Why a platform call failed.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ClientError {
    /// The platform answered with an error status. `code` is the JSON:API error `code`, when the
    /// body carried one.
    #[error("the platform answered {status}{}: {message}", code.as_deref().map(|c| format!(" ({c})")).unwrap_or_default())]
    Status { status: u16, code: Option<String>, message: String },
    /// The platform could not be reached, or did not answer in time.
    #[error("the platform could not be reached: {0}")]
    Transport(String),
    /// The platform's answer was not the expected JSON.
    #[error("the platform's answer could not be read: {0}")]
    Decode(String),
    /// The call cannot be made: no platform URL, tenant slug or access token.
    #[error("the platform cannot be called: {0}")]
    NotConfigured(String),
}

impl ClientError {
    /// The HTTP status, for [`ClientError::Status`].
    pub fn status(&self) -> Option<u16> {
        match self {
            ClientError::Status { status, .. } => Some(*status),
            _ => None,
        }
    }

    /// True when the platform refused the call (`401` or `403`): a missing grant, a disabled
    /// install or an undeclared credential. Retrying will not help.
    pub fn is_refused(&self) -> bool {
        matches!(self.status(), Some(401 | 403))
    }

    /// True when the platform answered `404`.
    pub fn is_not_found(&self) -> bool {
        self.status() == Some(404)
    }

    /// True when the failure may pass on retry: the platform was unreachable or answered `5xx`
    /// or `429`.
    pub fn is_transient(&self) -> bool {
        match self {
            ClientError::Transport(_) => true,
            ClientError::Status { status, .. } => *status >= 500 || *status == 429,
            _ => false,
        }
    }
}

/// One JSON:API resource: its id and attributes.
#[derive(Debug, Clone, PartialEq)]
pub struct Resource {
    pub id: String,
    pub attributes: Map<String, Value>,
}

impl Resource {
    /// An attribute as a non-blank string.
    pub fn str(&self, key: &str) -> Option<&str> {
        self.attributes.get(key).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty())
    }

    /// An attribute.
    pub fn get(&self, key: &str) -> Option<&Value> {
        self.attributes.get(key).filter(|v| !v.is_null())
    }
}

/// One page of a collection listing.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Page {
    pub data: Vec<Resource>,
    /// `meta.totalCount`: every matching record, not only this page.
    pub total_count: Option<u64>,
}

/// A JSON:API listing query: `filter[field][op]=value`, `sort`, `page[size]`, `page[number]`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Query {
    filters: Vec<(String, String, String)>,
    sort: Option<String>,
    page_size: Option<u32>,
    page_number: Option<u32>,
}

impl Query {
    /// An empty query.
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds `filter[field][op]=value`. Filters AND together.
    pub fn filter(mut self, field: impl Into<String>, op: impl Into<String>, value: impl Into<String>) -> Self {
        self.filters.push((field.into(), op.into(), value.into()));
        self
    }

    /// Adds `filter[field][eq]=value`.
    pub fn eq(self, field: impl Into<String>, value: impl Into<String>) -> Self {
        self.filter(field, "eq", value)
    }

    /// Sets `sort` (comma-separated fields, `-` for descending).
    pub fn sort(mut self, sort: impl Into<String>) -> Self {
        self.sort = Some(sort.into());
        self
    }

    /// Sets `page[size]` (the platform caps it at 200).
    pub fn page_size(mut self, size: u32) -> Self {
        self.page_size = Some(size);
        self
    }

    /// Sets `page[number]` (1-based).
    pub fn page_number(mut self, number: u32) -> Self {
        self.page_number = Some(number);
        self
    }

    /// The filters, as `(field, op, value)`.
    pub fn filters(&self) -> &[(String, String, String)] {
        &self.filters
    }

    /// The `sort` value.
    pub fn sort_spec(&self) -> Option<&str> {
        self.sort.as_deref()
    }

    /// The `page[size]` value.
    pub fn size(&self) -> Option<u32> {
        self.page_size
    }

    /// The `page[number]` value.
    pub fn number(&self) -> Option<u32> {
        self.page_number
    }

    /// The URL-encoded query string, without the leading `?`.
    pub fn to_query_string(&self) -> String {
        let mut out = url::form_urlencoded::Serializer::new(String::new());
        for (field, op, value) in &self.filters {
            out.append_pair(&format!("filter[{field}][{op}]"), value);
        }
        if let Some(sort) = &self.sort {
            out.append_pair("sort", sort);
        }
        if let Some(size) = self.page_size {
            out.append_pair("page[size]", &size.to_string());
        }
        if let Some(number) = self.page_number {
            out.append_pair("page[number]", &number.to_string());
        }
        out.finish()
    }
}

/// Calls the tenant's platform API as the install's service principal.
///
/// `Debug` never prints the access token.
#[derive(Clone)]
pub struct PlatformClient {
    http: reqwest::Client,
    base: String,
    token: String,
    traceparent: Option<String>,
}

impl fmt::Debug for PlatformClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PlatformClient").field("base", &self.base).finish_non_exhaustive()
    }
}

impl PlatformClient {
    /// A client for `{platform_url}/{tenant_slug}/api/...` with this access token.
    pub fn new(
        http: reqwest::Client,
        platform_url: &str,
        tenant_slug: &str,
        access_token: impl Into<String>,
        traceparent: Option<String>,
    ) -> Result<Self, ClientError> {
        let platform_url = platform_url.trim().trim_end_matches('/');
        let slug = tenant_slug.trim();
        if platform_url.is_empty() {
            return Err(ClientError::NotConfigured("no platform URL".into()));
        }
        if slug.is_empty() || !slug.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
            return Err(ClientError::NotConfigured("no usable tenant slug".into()));
        }
        let token = access_token.into();
        if token.trim().is_empty() {
            return Err(ClientError::NotConfigured("no access token".into()));
        }
        Ok(Self { http, base: format!("{platform_url}/{slug}"), token, traceparent })
    }

    /// An HTTP client suitable for platform calls: rustls, no redirects, a 10 s timeout.
    pub fn default_http() -> reqwest::Client {
        reqwest::Client::builder()
            .timeout(DEFAULT_TIMEOUT)
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("the default reqwest client builds")
    }

    /// `GET /api/{collection}?{query}`.
    pub async fn list(&self, collection: &str, query: &Query) -> Result<Page, ClientError> {
        let qs = query.to_query_string();
        let path = if qs.is_empty() { format!("/api/{collection}") } else { format!("/api/{collection}?{qs}") };
        let body = self.send(Method::GET, &path, None).await?;
        let data = match body.get("data") {
            Some(Value::Array(items)) => items.iter().map(resource).collect::<Result<Vec<_>, _>>()?,
            _ => return Err(ClientError::Decode("a listing without a data array".into())),
        };
        let total_count = body.pointer("/meta/totalCount").and_then(Value::as_u64);
        Ok(Page { data, total_count })
    }

    /// `GET /api/{collection}/{id}`; `None` when the platform answers `404`.
    pub async fn get(&self, collection: &str, id: &str) -> Result<Option<Resource>, ClientError> {
        match self.send(Method::GET, &format!("/api/{collection}/{}", encode_segment(id)), None).await {
            Ok(body) => body.get("data").map(resource).transpose(),
            Err(e) if e.is_not_found() => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// `POST /api/{collection}` with these attributes; the created resource.
    pub async fn create(&self, collection: &str, attributes: Map<String, Value>) -> Result<Resource, ClientError> {
        let body = json!({ "data": { "type": collection, "attributes": attributes } });
        let answer = self.send(Method::POST, &format!("/api/{collection}"), Some(body)).await?;
        answer.get("data").map(resource).transpose()?.ok_or_else(|| ClientError::Decode("no data".into()))
    }

    /// `PATCH /api/{collection}/{id}` with these attributes; the updated resource.
    pub async fn update(
        &self,
        collection: &str,
        id: &str,
        attributes: Map<String, Value>,
    ) -> Result<Resource, ClientError> {
        let body = json!({ "data": { "type": collection, "id": id, "attributes": attributes } });
        let path = format!("/api/{collection}/{}", encode_segment(id));
        let answer = self.send(Method::PATCH, &path, Some(body)).await?;
        answer.get("data").map(resource).transpose()?.ok_or_else(|| ClientError::Decode("no data".into()))
    }

    /// `GET /api/modules/self/credentials/{name}?purpose=...`: a tenant credential the manifest
    /// declares in `permissions.credentials`. Use it for this request only; never log it.
    pub async fn credential(&self, name: &str, purpose: Option<&str>) -> Result<ModuleCredential, ClientError> {
        let mut path = format!("/api/modules/self/credentials/{}", encode_segment(name));
        if let Some(purpose) = purpose {
            let qs = url::form_urlencoded::Serializer::new(String::new()).append_pair("purpose", purpose).finish();
            path.push('?');
            path.push_str(&qs);
        }
        let body = self.send(Method::GET, &path, None).await?;
        serde_json::from_value(body).map_err(|e| ClientError::Decode(format!("not a credential: {e}")))
    }

    async fn send(&self, method: Method, path: &str, body: Option<Value>) -> Result<Value, ClientError> {
        let mut request = self
            .http
            .request(method, format!("{}{}", self.base, path))
            .bearer_auth(&self.token)
            .header("Accept", "application/vnd.api+json, application/json");
        if let Some(traceparent) = &self.traceparent {
            request = request.header("traceparent", traceparent);
        }
        if let Some(body) = body {
            request = request.header("Content-Type", "application/vnd.api+json").body(body.to_string());
        }
        let response = request.send().await.map_err(|e| ClientError::Transport(transport_reason(e)))?;
        let status = response.status();
        let bytes = response.bytes().await.map_err(|e| ClientError::Transport(transport_reason(e)))?;
        if !status.is_success() {
            return Err(status_error(status, &bytes));
        }
        if bytes.is_empty() {
            return Ok(Value::Null);
        }
        serde_json::from_slice(&bytes).map_err(|e| ClientError::Decode(e.to_string()))
    }
}

/// What module code needs from the platform: tenant records over JSON:API and its declared
/// credentials. [`PlatformClient`] is the real one; module logic written against this trait can be
/// tested with `kelta_module::testing::MemoryPlatform` (feature `testing`).
pub trait Platform: Send + Sync {
    /// `GET /api/{collection}?{query}`.
    fn list(&self, collection: &str, query: &Query) -> impl Future<Output = Result<Page, ClientError>> + Send;

    /// `GET /api/{collection}/{id}`; `None` when it does not exist.
    fn get(&self, collection: &str, id: &str) -> impl Future<Output = Result<Option<Resource>, ClientError>> + Send;

    /// `POST /api/{collection}`.
    fn create(
        &self,
        collection: &str,
        attributes: Map<String, Value>,
    ) -> impl Future<Output = Result<Resource, ClientError>> + Send;

    /// `PATCH /api/{collection}/{id}`.
    fn update(
        &self,
        collection: &str,
        id: &str,
        attributes: Map<String, Value>,
    ) -> impl Future<Output = Result<Resource, ClientError>> + Send;

    /// `GET /api/modules/self/credentials/{name}?purpose=...`.
    fn credential(
        &self,
        name: &str,
        purpose: Option<&str>,
    ) -> impl Future<Output = Result<ModuleCredential, ClientError>> + Send;
}

impl Platform for PlatformClient {
    fn list(&self, collection: &str, query: &Query) -> impl Future<Output = Result<Page, ClientError>> + Send {
        PlatformClient::list(self, collection, query)
    }

    fn get(&self, collection: &str, id: &str) -> impl Future<Output = Result<Option<Resource>, ClientError>> + Send {
        PlatformClient::get(self, collection, id)
    }

    fn create(
        &self,
        collection: &str,
        attributes: Map<String, Value>,
    ) -> impl Future<Output = Result<Resource, ClientError>> + Send {
        PlatformClient::create(self, collection, attributes)
    }

    fn update(
        &self,
        collection: &str,
        id: &str,
        attributes: Map<String, Value>,
    ) -> impl Future<Output = Result<Resource, ClientError>> + Send {
        PlatformClient::update(self, collection, id, attributes)
    }

    fn credential(
        &self,
        name: &str,
        purpose: Option<&str>,
    ) -> impl Future<Output = Result<ModuleCredential, ClientError>> + Send {
        PlatformClient::credential(self, name, purpose)
    }
}

fn resource(value: &Value) -> Result<Resource, ClientError> {
    let id = match value.get("id") {
        Some(Value::String(id)) => id.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => return Err(ClientError::Decode("a resource without an id".into())),
    };
    let attributes = match value.get("attributes") {
        Some(Value::Object(map)) => map.clone(),
        None | Some(Value::Null) => Map::new(),
        Some(_) => return Err(ClientError::Decode("attributes is not an object".into())),
    };
    Ok(Resource { id, attributes })
}

fn status_error(status: StatusCode, body: &[u8]) -> ClientError {
    let parsed: Option<Value> = serde_json::from_slice(body).ok();
    let first = parsed.as_ref().and_then(|v| v.pointer("/errors/0"));
    let code = first.and_then(|e| e.get("code")).and_then(Value::as_str).map(str::to_string);
    let message = first
        .and_then(|e| e.get("detail").or_else(|| e.get("title")))
        .and_then(Value::as_str)
        .or_else(|| parsed.as_ref().and_then(|v| v.get("error")).and_then(Value::as_str))
        .unwrap_or_else(|| status.canonical_reason().unwrap_or("error"))
        .chars()
        .take(300)
        .collect();
    ClientError::Status { status: status.as_u16(), code, message }
}

/// A transport error's reason, without the URL (which may carry ids worth keeping out of logs).
fn transport_reason(error: reqwest::Error) -> String {
    if error.is_timeout() {
        "timed out".into()
    } else if error.is_connect() {
        "could not connect".into()
    } else {
        error.without_url().to_string()
    }
}

fn encode_segment(segment: &str) -> String {
    url::form_urlencoded::byte_serialize(segment.as_bytes()).collect::<String>().replace('+', "%20")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_strings_use_bracket_syntax() {
        let q = Query::new()
            .eq("userId", "u 1")
            .filter("expiresAt", "lt", "2026-01-01T00:00:00Z")
            .sort("-createdAt")
            .page_size(1);
        assert_eq!(
            q.to_query_string(),
            "filter%5BuserId%5D%5Beq%5D=u+1&filter%5BexpiresAt%5D%5Blt%5D=2026-01-01T00%3A00%3A00Z&sort=-createdAt&page%5Bsize%5D=1"
        );
        assert_eq!(Query::new().to_query_string(), "");
    }

    #[test]
    fn status_errors_read_the_jsonapi_envelope() {
        let body = br#"{"errors":[{"status":"403","code":"CREDENTIAL_NOT_DECLARED","detail":"not declared"}]}"#;
        let e = status_error(StatusCode::FORBIDDEN, body);
        assert_eq!(
            e,
            ClientError::Status {
                status: 403,
                code: Some("CREDENTIAL_NOT_DECLARED".into()),
                message: "not declared".into()
            }
        );
        assert!(e.is_refused());
        assert!(!e.is_transient());
        let e = status_error(StatusCode::SERVICE_UNAVAILABLE, b"oops");
        assert_eq!(e.status(), Some(503));
        assert!(e.is_transient());
    }

    #[test]
    fn clients_need_a_slug_url_and_token() {
        let http = reqwest::Client::new();
        assert!(PlatformClient::new(http.clone(), "", "acme", "t", None).is_err());
        assert!(PlatformClient::new(http.clone(), "https://p.test", "", "t", None).is_err());
        assert!(PlatformClient::new(http.clone(), "https://p.test", "../x", "t", None).is_err());
        assert!(PlatformClient::new(http.clone(), "https://p.test", "acme", " ", None).is_err());
        let client = PlatformClient::new(http, "https://p.test/", "acme", "secret-token", None).unwrap();
        assert!(!format!("{client:?}").contains("secret-token"));
    }

    #[test]
    fn segments_are_percent_encoded() {
        assert_eq!(encode_segment("a b/c"), "a%20b%2Fc");
    }
}
