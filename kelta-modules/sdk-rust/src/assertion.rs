//! Verification of the platform assertion every module request carries
//! (`Authorization: Bearer <ES256 JWT>`, spec `9-external-modules.md` §3.2).
//!
//! [`AssertionVerifier::verify`] performs every check the protocol requires in one call, in this
//! order, and fails closed on the first that does not hold:
//!
//! 1. a `Bearer` token whose header names `ES256` and a `kid`;
//! 2. the signature, against the key named by `kid` in the platform JWKS
//!    (`{issuer}/.well-known/jwks.json`, cached; an unknown `kid` triggers one throttled refetch,
//!    which is how a key rotation reaches the module);
//! 3. `iss` equals the configured issuer and `aud` equals the module id;
//! 4. `exp` is in the future and `iat` is not, both within the configured skew, and the token
//!    lives no longer than [`VerifierConfig::max_lifetime`];
//! 5. `htm` equals the request method and `htu` equals one of the module's registered base URLs
//!    followed by the request's exact path and query;
//! 6. `body_sha256` equals the base64url (unpadded) SHA-256 of the raw body bytes;
//! 7. `jti` has not been seen before within the token's lifetime (replay).
//!
//! What a module may know about the call — tenant, install, acting user — comes from the verified
//! claims only ([`VerifiedAssertion`]). Nothing here reads identity from a request header.

use std::collections::HashMap;
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use jsonwebtoken::{Algorithm, DecodingKey, Validation, errors::ErrorKind};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::sync::{Mutex as AsyncMutex, RwLock};

use crate::protocol::Actor;

/// The largest JWKS document the verifier reads.
const MAX_JWKS_BYTES: usize = 64 * 1024;

/// Configuration of an [`AssertionVerifier`].
#[derive(Debug, Clone)]
pub struct VerifierConfig {
    /// The `iss` every assertion must carry, e.g. `https://api.kelta.io/api/modules`.
    pub issuer: String,
    /// The module id; every assertion's `aud` must equal it.
    pub module_id: String,
    /// The base URL(s) the module is registered under, exactly as a tenant admin typed them at
    /// install (`htu` = base URL + path + query). Usually one.
    pub base_urls: Vec<String>,
    /// Where to fetch the JWKS. Defaults to `{issuer}/.well-known/jwks.json`; set it when the
    /// module reaches the platform by another route than the public issuer URL (an in-cluster
    /// gateway). `iss` is still checked against [`Self::issuer`].
    pub jwks_url: Option<String>,
    /// Allowed clock skew for `exp` and `iat`. The protocol says 30 s.
    pub leeway: Duration,
    /// The longest `exp - iat` accepted. The platform signs for 60 s.
    pub max_lifetime: Duration,
    /// How long a fetched JWKS is used before it is refetched on the next request.
    pub jwks_cache_ttl: Duration,
    /// The shortest time between two JWKS fetches caused by an unknown `kid`.
    pub min_refetch_interval: Duration,
}

impl VerifierConfig {
    /// A configuration with the protocol's defaults: 30 s skew, 5 min lifetime cap, a 10 min JWKS
    /// cache and at most one unknown-`kid` refetch every 10 s.
    pub fn new(issuer: impl Into<String>, module_id: impl Into<String>, base_urls: Vec<String>) -> Self {
        Self {
            issuer: issuer.into(),
            module_id: module_id.into(),
            base_urls,
            jwks_url: None,
            leeway: Duration::from_secs(30),
            max_lifetime: Duration::from_secs(300),
            jwks_cache_ttl: Duration::from_secs(600),
            min_refetch_interval: Duration::from_secs(10),
        }
    }

    /// The JWKS URL: [`Self::jwks_url`], else `{issuer}/.well-known/jwks.json`.
    pub fn resolved_jwks_url(&self) -> String {
        match &self.jwks_url {
            Some(url) => url.clone(),
            None => format!("{}/.well-known/jwks.json", self.issuer.trim_end_matches('/')),
        }
    }

    fn validate(&self) -> Result<Vec<String>, ConfigError> {
        if self.issuer.trim().is_empty() {
            return Err(ConfigError("issuer is required".into()));
        }
        if self.module_id.trim().is_empty() {
            return Err(ConfigError("module_id is required".into()));
        }
        if self.base_urls.is_empty() {
            return Err(ConfigError("at least one base URL is required".into()));
        }
        let mut bases = Vec::with_capacity(self.base_urls.len());
        for base in &self.base_urls {
            let trimmed = base.trim().trim_end_matches('/');
            let parsed =
                url::Url::parse(trimmed).map_err(|e| ConfigError(format!("base URL '{trimmed}' is not a URL: {e}")))?;
            if !matches!(parsed.scheme(), "http" | "https") || parsed.host_str().is_none() {
                return Err(ConfigError(format!("base URL '{trimmed}' must be http(s) with a host")));
            }
            if parsed.query().is_some() || parsed.fragment().is_some() {
                return Err(ConfigError(format!("base URL '{trimmed}' must not carry a query or fragment")));
            }
            bases.push(trimmed.to_string());
        }
        Ok(bases)
    }
}

/// A configuration the verifier cannot run with.
#[derive(Debug, Clone, thiserror::Error)]
#[error("invalid module verifier configuration: {0}")]
pub struct ConfigError(pub String);

/// Why an assertion was refused. Every variant is answered with `401`; the variant is for logs.
/// No variant carries token material.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum VerifyError {
    #[error("no Authorization header")]
    MissingAuthorization,
    #[error("the Authorization header is not a Bearer token")]
    MalformedAuthorization,
    #[error("the token is not a well-formed JWS")]
    MalformedToken,
    #[error("algorithm {0} is not ES256")]
    UnsupportedAlgorithm(String),
    #[error("the token header names no kid")]
    MissingKeyId,
    #[error("no key '{0}' in the platform JWKS")]
    UnknownKeyId(String),
    #[error("the platform JWKS could not be fetched: {0}")]
    JwksUnavailable(String),
    #[error("the signature does not verify")]
    BadSignature,
    #[error("the token has expired")]
    Expired,
    #[error("the token was issued in the future")]
    IssuedInFuture,
    #[error("the token lives longer than allowed")]
    LifetimeTooLong,
    #[error("the issuer is not the configured one")]
    WrongIssuer,
    #[error("the audience is not this module")]
    WrongAudience,
    #[error("required claim '{0}' is missing")]
    MissingClaim(String),
    #[error("the claims are malformed")]
    MalformedClaims,
    #[error("htm does not match the request method")]
    MethodMismatch,
    #[error("htu does not match this request's URL")]
    UrlMismatch,
    #[error("body_sha256 does not match the request body")]
    BodyHashMismatch,
    #[error("the token was already used (jti replay)")]
    Replayed,
}

/// What the verifier needs from an incoming request. Build it from the raw request, before any
/// body parsing.
#[derive(Debug, Clone, Copy)]
pub struct IncomingRequest<'a> {
    /// The request method, e.g. `POST`.
    pub method: &'a str,
    /// The path and query exactly as received, e.g. `/kelta/v1/routes/me?page=2`.
    pub path_and_query: &'a str,
    /// The raw `Authorization` header value.
    pub authorization: Option<&'a str>,
    /// The raw body bytes (empty for none).
    pub body: &'a [u8],
}

/// The verified identity of a platform call. Built only from signed claims.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedAssertion {
    /// The tenant being served (`tenant_id`).
    pub tenant_id: String,
    /// The tenant's URL slug (`tenant_slug`), the path prefix of its platform API.
    pub tenant_slug: Option<String>,
    /// The install id (`sub`, the `tenant_module` row).
    pub install_id: String,
    /// The module id (`aud`).
    pub module_id: String,
    /// The acting user (`act`), when a person's action caused the call.
    pub actor: Option<Actor>,
    /// The token id (`jti`).
    pub jti: String,
    /// `iat`, seconds since the epoch.
    pub issued_at: i64,
    /// `exp`, seconds since the epoch.
    pub expires_at: i64,
}

/// Where the verifier gets the platform's JWKS document from.
pub trait JwksSource: Send + Sync {
    /// Fetches the JWKS JSON text.
    fn fetch(&self) -> Pin<Box<dyn Future<Output = Result<String, String>> + Send + '_>>;
}

/// Fetches the JWKS over HTTP(S).
pub struct HttpJwksSource {
    client: reqwest::Client,
    url: String,
}

impl HttpJwksSource {
    /// A source for `url`, with a 5 s timeout and no redirects.
    pub fn new(url: impl Into<String>) -> Result<Self, ConfigError> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|e| ConfigError(format!("could not build the JWKS client: {e}")))?;
        Ok(Self { client, url: url.into() })
    }
}

impl JwksSource for HttpJwksSource {
    fn fetch(&self) -> Pin<Box<dyn Future<Output = Result<String, String>> + Send + '_>> {
        Box::pin(async move {
            let response = self.client.get(&self.url).send().await.map_err(|e| e.to_string())?;
            if !response.status().is_success() {
                return Err(format!("{} answered {}", self.url, response.status()));
            }
            let bytes = response.bytes().await.map_err(|e| e.to_string())?;
            if bytes.len() > MAX_JWKS_BYTES {
                return Err(format!("the JWKS is larger than {MAX_JWKS_BYTES} bytes"));
            }
            String::from_utf8(bytes.to_vec()).map_err(|e| e.to_string())
        })
    }
}

#[derive(Default)]
struct KeyCache {
    keys: HashMap<String, Arc<DecodingKey>>,
    fetched_at: Option<Instant>,
    last_attempt: Option<Instant>,
}

#[derive(Default)]
struct ReplayCache {
    /// jti → the instant (epoch seconds) after which it can no longer be presented.
    seen: HashMap<String, i64>,
    last_prune: i64,
}

impl ReplayCache {
    const PRUNE_EVERY_SECS: i64 = 10;
    const PRUNE_ABOVE: usize = 10_000;

    fn check_and_record(&mut self, jti: &str, forget_after: i64, now: i64) -> Result<(), VerifyError> {
        if now - self.last_prune >= Self::PRUNE_EVERY_SECS || self.seen.len() > Self::PRUNE_ABOVE {
            self.seen.retain(|_, until| *until >= now);
            self.last_prune = now;
        }
        if let Some(until) = self.seen.get(jti)
            && *until >= now
        {
            return Err(VerifyError::Replayed);
        }
        self.seen.insert(jti.to_string(), forget_after);
        Ok(())
    }
}

/// The claims the verifier reads. `iss`, `aud` and `exp` are checked by `jsonwebtoken`.
#[derive(Deserialize)]
struct Claims {
    sub: String,
    tenant_id: String,
    #[serde(default)]
    tenant_slug: Option<String>,
    #[serde(default)]
    act: Option<Actor>,
    htm: String,
    htu: String,
    body_sha256: String,
    iat: i64,
    exp: i64,
    jti: String,
}

/// Verifies platform assertions. Cheap to share: wrap it in an `Arc`.
pub struct AssertionVerifier {
    config: VerifierConfig,
    base_urls: Vec<String>,
    jwks: Arc<dyn JwksSource>,
    keys: RwLock<KeyCache>,
    refetch: AsyncMutex<()>,
    replay: Mutex<ReplayCache>,
}

impl fmt::Debug for AssertionVerifier {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AssertionVerifier").field("config", &self.config).finish_non_exhaustive()
    }
}

impl AssertionVerifier {
    /// A verifier fetching the JWKS over HTTP from [`VerifierConfig::resolved_jwks_url`].
    pub fn new(config: VerifierConfig) -> Result<Self, ConfigError> {
        let source = HttpJwksSource::new(config.resolved_jwks_url())?;
        Self::with_source(config, Arc::new(source))
    }

    /// A verifier with its own JWKS source (tests, or a custom transport).
    pub fn with_source(config: VerifierConfig, jwks: Arc<dyn JwksSource>) -> Result<Self, ConfigError> {
        let base_urls = config.validate()?;
        Ok(Self {
            config,
            base_urls,
            jwks,
            keys: RwLock::new(KeyCache::default()),
            refetch: AsyncMutex::new(()),
            replay: Mutex::new(ReplayCache::default()),
        })
    }

    /// The configuration this verifier runs with.
    pub fn config(&self) -> &VerifierConfig {
        &self.config
    }

    /// Verifies one request. See the module documentation for the checks and their order.
    pub async fn verify(&self, request: IncomingRequest<'_>) -> Result<VerifiedAssertion, VerifyError> {
        let token = bearer(request.authorization)?;
        let header = jsonwebtoken::decode_header(token).map_err(|_| VerifyError::MalformedToken)?;
        if header.alg != Algorithm::ES256 {
            return Err(VerifyError::UnsupportedAlgorithm(format!("{:?}", header.alg)));
        }
        let kid = header.kid.filter(|k| !k.is_empty()).ok_or(VerifyError::MissingKeyId)?;
        let key = self.key(&kid).await?;

        let mut validation = Validation::new(Algorithm::ES256);
        validation.leeway = self.config.leeway.as_secs();
        validation.set_issuer(&[self.config.issuer.as_str()]);
        validation.set_audience(&[self.config.module_id.as_str()]);
        validation.set_required_spec_claims(&["exp", "iss", "aud", "sub"]);
        let claims =
            jsonwebtoken::decode::<Claims>(token, &key, &validation).map_err(|e| map_jwt_error(e.into_kind()))?.claims;

        let now = unix_now();
        let leeway = self.config.leeway.as_secs() as i64;
        if claims.iat > now + leeway {
            return Err(VerifyError::IssuedInFuture);
        }
        if claims.exp - claims.iat > self.config.max_lifetime.as_secs() as i64 {
            return Err(VerifyError::LifetimeTooLong);
        }
        if claims.htm != request.method.to_ascii_uppercase() {
            return Err(VerifyError::MethodMismatch);
        }
        if !self.base_urls.iter().any(|base| matches_url(&claims.htu, base, request.path_and_query)) {
            return Err(VerifyError::UrlMismatch);
        }
        let actual = body_sha256(request.body);
        if !bool::from(actual.as_bytes().ct_eq(claims.body_sha256.as_bytes())) {
            return Err(VerifyError::BodyHashMismatch);
        }
        if claims.jti.is_empty() {
            return Err(VerifyError::MissingClaim("jti".into()));
        }
        self.replay.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).check_and_record(
            &claims.jti,
            claims.exp + leeway,
            now,
        )?;

        Ok(VerifiedAssertion {
            tenant_id: claims.tenant_id,
            tenant_slug: claims.tenant_slug.filter(|s| !s.is_empty()),
            install_id: claims.sub,
            module_id: self.config.module_id.clone(),
            actor: claims.act,
            jti: claims.jti,
            issued_at: claims.iat,
            expires_at: claims.exp,
        })
    }

    /// The key for `kid`: from the cache while it is fresh; otherwise, or for an unknown `kid`,
    /// after one fetch (single-flight, throttled by [`VerifierConfig::min_refetch_interval`]).
    async fn key(&self, kid: &str) -> Result<Arc<DecodingKey>, VerifyError> {
        {
            let cache = self.keys.read().await;
            if let Some(key) = cache.keys.get(kid)
                && !self.stale(&cache)
            {
                return Ok(key.clone());
            }
        }
        let _single_flight = self.refetch.lock().await;
        {
            let cache = self.keys.read().await;
            let fresh = !self.stale(&cache);
            if let Some(key) = cache.keys.get(kid)
                && fresh
            {
                return Ok(key.clone());
            }
            let throttled = cache.last_attempt.is_some_and(|last| last.elapsed() < self.config.min_refetch_interval);
            if throttled {
                // Too soon to ask again: answer from what we have, even if stale.
                return cache.keys.get(kid).cloned().ok_or_else(|| VerifyError::UnknownKeyId(kid.to_string()));
            }
        }
        let fetched = self.jwks.fetch().await.and_then(|text| parse_jwks(&text));
        let mut cache = self.keys.write().await;
        cache.last_attempt = Some(Instant::now());
        match fetched {
            Ok(keys) => {
                cache.keys = keys;
                cache.fetched_at = Some(Instant::now());
            }
            Err(reason) => {
                tracing::warn!(reason = %reason, "could not fetch the platform JWKS");
                if cache.keys.is_empty() {
                    return Err(VerifyError::JwksUnavailable(reason));
                }
            }
        }
        cache.keys.get(kid).cloned().ok_or_else(|| VerifyError::UnknownKeyId(kid.to_string()))
    }

    fn stale(&self, cache: &KeyCache) -> bool {
        cache.fetched_at.is_none_or(|at| at.elapsed() >= self.config.jwks_cache_ttl)
    }
}

/// Base64url (unpadded) SHA-256 of `body`: the `body_sha256` claim's form.
pub fn body_sha256(body: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(body))
}

fn bearer(authorization: Option<&str>) -> Result<&str, VerifyError> {
    let value = authorization.ok_or(VerifyError::MissingAuthorization)?.trim();
    let (scheme, token) = value.split_once(' ').ok_or(VerifyError::MalformedAuthorization)?;
    let token = token.trim();
    if !scheme.eq_ignore_ascii_case("bearer") || token.is_empty() {
        return Err(VerifyError::MalformedAuthorization);
    }
    Ok(token)
}

/// `htu` must be exactly `base` followed by the request's path and query.
fn matches_url(htu: &str, base: &str, path_and_query: &str) -> bool {
    htu.len() == base.len() + path_and_query.len() && htu.starts_with(base) && htu[base.len()..] == *path_and_query
}

fn map_jwt_error(kind: ErrorKind) -> VerifyError {
    match kind {
        ErrorKind::InvalidSignature | ErrorKind::InvalidEcdsaKey => VerifyError::BadSignature,
        ErrorKind::ExpiredSignature => VerifyError::Expired,
        ErrorKind::InvalidIssuer => VerifyError::WrongIssuer,
        ErrorKind::InvalidAudience => VerifyError::WrongAudience,
        ErrorKind::MissingRequiredClaim(claim) => VerifyError::MissingClaim(claim),
        ErrorKind::InvalidAlgorithm => VerifyError::UnsupportedAlgorithm("mismatch".into()),
        ErrorKind::Json(_) | ErrorKind::InvalidClaimFormat(_) => VerifyError::MalformedClaims,
        _ => VerifyError::MalformedToken,
    }
}

#[derive(Deserialize)]
struct Jwks {
    #[serde(default)]
    keys: Vec<Jwk>,
}

#[derive(Deserialize)]
struct Jwk {
    #[serde(default)]
    kty: String,
    #[serde(default)]
    crv: Option<String>,
    #[serde(default)]
    kid: Option<String>,
    #[serde(default)]
    alg: Option<String>,
    #[serde(default, rename = "use")]
    usage: Option<String>,
    #[serde(default)]
    x: Option<String>,
    #[serde(default)]
    y: Option<String>,
}

/// The P-256 signing keys of a JWKS document, by `kid`. Keys of any other kind are skipped.
fn parse_jwks(text: &str) -> Result<HashMap<String, Arc<DecodingKey>>, String> {
    let jwks: Jwks = serde_json::from_str(text).map_err(|e| format!("the JWKS is not valid JSON: {e}"))?;
    let mut keys = HashMap::new();
    for jwk in jwks.keys {
        let usable = jwk.kty == "EC"
            && jwk.crv.as_deref() == Some("P-256")
            && jwk.alg.as_deref().is_none_or(|alg| alg == "ES256")
            && jwk.usage.as_deref().is_none_or(|usage| usage == "sig");
        let (Some(kid), Some(x), Some(y)) = (jwk.kid, jwk.x, jwk.y) else {
            continue;
        };
        if !usable {
            continue;
        }
        match DecodingKey::from_ec_components(&x, &y) {
            Ok(key) => {
                keys.insert(kid, Arc::new(key));
            }
            Err(e) => tracing::warn!(kid = %kid, error = %e, "skipping an unusable JWKS key"),
        }
    }
    Ok(keys)
}

fn unix_now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bearer_parsing() {
        assert_eq!(bearer(None), Err(VerifyError::MissingAuthorization));
        assert_eq!(bearer(Some("Basic abc")), Err(VerifyError::MalformedAuthorization));
        assert_eq!(bearer(Some("Bearer")), Err(VerifyError::MalformedAuthorization));
        assert_eq!(bearer(Some("Bearer   ")), Err(VerifyError::MalformedAuthorization));
        assert_eq!(bearer(Some("bearer a.b.c")), Ok("a.b.c"));
    }

    #[test]
    fn url_matching_is_exact() {
        let base = "https://m.example.test";
        assert!(matches_url("https://m.example.test/kelta/v1/hooks", base, "/kelta/v1/hooks"));
        assert!(matches_url("https://m.example.test/kelta/v1/routes/me?page=2", base, "/kelta/v1/routes/me?page=2"));
        assert!(!matches_url("https://m.example.test/kelta/v1/routes/me", base, "/kelta/v1/routes/me?page=2"));
        assert!(!matches_url("https://m.example.test.evil/kelta/v1/hooks", base, "/kelta/v1/hooks"));
        assert!(!matches_url("http://m.example.test/kelta/v1/hooks", base, "/kelta/v1/hooks"));
    }

    #[test]
    fn body_hash_of_empty_is_the_known_value() {
        assert_eq!(body_sha256(b""), "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU");
    }

    #[test]
    fn config_rejects_unusable_base_urls() {
        let bad = |base: &str| VerifierConfig::new("https://i.test", "m-1", vec![base.into()]).validate().is_err();
        assert!(bad("not a url"));
        assert!(bad("ftp://m.test"));
        assert!(bad("https://m.test?x=1"));
        assert!(VerifierConfig::new("https://i.test", "m-1", vec![]).validate().is_err());
        assert!(VerifierConfig::new("", "m-1", vec!["https://m.test".into()]).validate().is_err());
        let ok = VerifierConfig::new("https://i.test", "m-1", vec!["https://m.test/".into()]);
        assert_eq!(ok.validate().unwrap(), vec!["https://m.test".to_string()]);
        assert_eq!(ok.resolved_jwks_url(), "https://i.test/.well-known/jwks.json");
    }

    #[test]
    fn jwks_parsing_keeps_only_p256_signing_keys() {
        let text = r#"{"keys":[
            {"kty":"RSA","kid":"rsa","n":"x","e":"AQAB"},
            {"kty":"EC","crv":"P-384","kid":"p384","x":"a","y":"b"},
            {"kty":"EC","crv":"P-256","kid":"enc","use":"enc","x":"a","y":"b"},
            {"kty":"EC","crv":"P-256","x":"a","y":"b"}
        ]}"#;
        assert!(parse_jwks(text).unwrap().is_empty());
        assert!(parse_jwks("not json").is_err());
    }

    #[test]
    fn replay_cache_forgets_after_expiry() {
        let mut cache = ReplayCache::default();
        assert!(cache.check_and_record("a", 100, 50).is_ok());
        assert_eq!(cache.check_and_record("a", 100, 60), Err(VerifyError::Replayed));
        // Past its lifetime the jti is pruned: the token itself is expired by then anyway.
        assert!(cache.check_and_record("a", 200, 150).is_ok());
    }
}
