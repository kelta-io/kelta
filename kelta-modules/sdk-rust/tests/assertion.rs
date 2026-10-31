//! Every way `AssertionVerifier::verify` refuses a request, and the ways it must accept one.

mod common;

use std::time::Duration;

use common::*;
use jsonwebtoken::{Algorithm, EncodingKey, Header};
use kelta_module::assertion::{IncomingRequest, VerifyError};
use kelta_module::protocol::Actor;
use serde_json::{Value, json};

const PATH: &str = "/kelta/v1/hooks";
const BODY: &[u8] = br#"{"tenantId":"t"}"#;

fn request<'a>(authorization: Option<&'a str>) -> IncomingRequest<'a> {
    IncomingRequest { method: "POST", path_and_query: PATH, authorization, body: BODY }
}

async fn verify_claims(claims: &Value) -> Result<kelta_module::VerifiedAssertion, VerifyError> {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    let auth = bearer(&key.sign_own(claims));
    verifier.verify(request(Some(&auth))).await
}

fn with(mut claims: Value, key: &str, value: Value) -> Value {
    claims[key] = value;
    claims
}

fn without(mut claims: Value, key: &str) -> Value {
    claims.as_object_mut().unwrap().remove(key);
    claims
}

#[tokio::test]
async fn accepts_a_valid_assertion_and_exposes_only_its_claims() {
    let verified = verify_claims(&claims("POST", PATH, BODY)).await.expect("valid");
    assert_eq!(verified.tenant_id, TENANT_ID);
    assert_eq!(verified.tenant_slug.as_deref(), Some("acme"));
    assert_eq!(verified.install_id, "install-1");
    assert_eq!(verified.module_id, MODULE_ID);
    assert_eq!(
        verified.actor,
        Some(Actor { sub: "user-1".into(), user_type: Some("PORTAL".into()), profile_id: None })
    );
}

#[tokio::test]
async fn accepts_no_actor_a_null_slug_and_an_audience_array() {
    let claims =
        with(with(without(claims("POST", PATH, BODY), "act"), "tenant_slug", Value::Null), "aud", json!([MODULE_ID]));
    let verified = verify_claims(&claims).await.expect("valid");
    assert_eq!(verified.actor, None);
    assert_eq!(verified.tenant_slug, None);
}

#[tokio::test]
async fn accepts_clock_skew_within_the_leeway() {
    let iat = now() - 80;
    let skewed = with(with(claims("POST", PATH, BODY), "iat", json!(iat)), "exp", json!(iat + 60));
    assert!(verify_claims(&skewed).await.is_ok(), "expired 20 s ago, inside the 30 s leeway");
    let early = with(with(claims("POST", PATH, BODY), "iat", json!(now() + 20)), "exp", json!(now() + 80));
    assert!(verify_claims(&early).await.is_ok(), "issued 20 s ahead, inside the 30 s leeway");
}

#[tokio::test]
async fn refuses_a_request_without_authorization() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    assert_eq!(verifier.verify(request(None)).await, Err(VerifyError::MissingAuthorization));
}

#[tokio::test]
async fn refuses_a_non_bearer_authorization() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    for auth in ["Basic dXNlcjpwYXNz", "Bearer", "Bearer    ", "token"] {
        assert_eq!(verifier.verify(request(Some(auth))).await, Err(VerifyError::MalformedAuthorization), "{auth}");
    }
}

#[tokio::test]
async fn refuses_a_malformed_token() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    for token in ["Bearer not-a-jwt", "Bearer a.b.c", "Bearer ...."] {
        assert_eq!(verifier.verify(request(Some(token))).await, Err(VerifyError::MalformedToken), "{token}");
    }
}

#[tokio::test]
async fn refuses_any_algorithm_but_es256() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    let mut header = Header::new(Algorithm::HS256);
    header.kid = Some("k1".into());
    let token =
        jsonwebtoken::encode(&header, &claims("POST", PATH, BODY), &EncodingKey::from_secret(b"guess")).unwrap();
    assert!(matches!(verifier.verify(request(Some(&bearer(&token)))).await, Err(VerifyError::UnsupportedAlgorithm(_))));
    // `alg: none` never reaches signature checking either.
    let none = format!(
        "{}.{}.",
        base64_url(br#"{"alg":"none","kid":"k1"}"#),
        base64_url(claims("POST", PATH, BODY).to_string().as_bytes())
    );
    let refused = verifier.verify(request(Some(&bearer(&none)))).await.unwrap_err();
    assert!(matches!(refused, VerifyError::MalformedToken | VerifyError::UnsupportedAlgorithm(_)), "{refused:?}");
}

#[tokio::test]
async fn refuses_a_token_without_a_kid() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    let token = key.sign(&claims("POST", PATH, BODY), None);
    assert_eq!(verifier.verify(request(Some(&bearer(&token)))).await, Err(VerifyError::MissingKeyId));
}

#[tokio::test]
async fn refuses_an_unknown_kid_after_one_throttled_refetch() {
    let key = PlatformKey::generate("k1");
    let jwks = TestJwks::with_keys(&[&key]);
    let verifier = verifier(jwks.clone());
    let auth = bearer(&key.sign(&claims("POST", PATH, BODY), Some("rotated-away")));
    assert_eq!(verifier.verify(request(Some(&auth))).await, Err(VerifyError::UnknownKeyId("rotated-away".into())));
    assert_eq!(jwks.fetches(), 1);
    // A second unknown kid inside the refetch interval does not hit the platform again.
    let auth = bearer(&key.sign(&claims("POST", PATH, BODY), Some("other")));
    assert_eq!(verifier.verify(request(Some(&auth))).await, Err(VerifyError::UnknownKeyId("other".into())));
    assert_eq!(jwks.fetches(), 1);
}

#[tokio::test]
async fn refetches_the_jwks_on_an_unknown_kid_to_follow_a_rotation() {
    let old = PlatformKey::generate("old");
    let new = PlatformKey::generate("new");
    let jwks = TestJwks::with_keys(&[&old]);
    let mut config = config();
    config.min_refetch_interval = Duration::ZERO;
    let verifier = verifier_with(config, jwks.clone());
    let auth = bearer(&old.sign_own(&claims("POST", PATH, BODY)));
    verifier.verify(request(Some(&auth))).await.expect("old key");
    assert_eq!(jwks.fetches(), 1);
    // Cached: a second call with the same kid does not refetch.
    let auth = bearer(&old.sign_own(&claims("POST", PATH, BODY)));
    verifier.verify(request(Some(&auth))).await.expect("old key, cached");
    assert_eq!(jwks.fetches(), 1);

    jwks.set_keys(&[&old, &new]);
    let auth = bearer(&new.sign_own(&claims("POST", PATH, BODY)));
    verifier.verify(request(Some(&auth))).await.expect("new key after refetch");
    assert_eq!(jwks.fetches(), 2);
}

#[tokio::test]
async fn refuses_when_the_jwks_cannot_be_fetched() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::failing());
    let auth = bearer(&key.sign_own(&claims("POST", PATH, BODY)));
    assert!(matches!(verifier.verify(request(Some(&auth))).await, Err(VerifyError::JwksUnavailable(_))));
}

#[tokio::test]
async fn keeps_serving_cached_keys_when_a_refetch_fails() {
    let key = PlatformKey::generate("k1");
    let jwks = TestJwks::with_keys(&[&key]);
    let mut config = config();
    config.jwks_cache_ttl = Duration::ZERO;
    config.min_refetch_interval = Duration::ZERO;
    let verifier = verifier_with(config, jwks.clone());
    let auth = bearer(&key.sign_own(&claims("POST", PATH, BODY)));
    verifier.verify(request(Some(&auth))).await.expect("first");
    *jwks.document.lock().unwrap() = Err("platform down".into());
    let auth = bearer(&key.sign_own(&claims("POST", PATH, BODY)));
    verifier.verify(request(Some(&auth))).await.expect("stale key still verifies");
    assert_eq!(jwks.fetches(), 2);
}

#[tokio::test]
async fn refuses_a_signature_by_another_key() {
    let platform = PlatformKey::generate("k1");
    let attacker = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&platform]));
    let auth = bearer(&attacker.sign_own(&claims("POST", PATH, BODY)));
    assert_eq!(verifier.verify(request(Some(&auth))).await, Err(VerifyError::BadSignature));
}

#[tokio::test]
async fn refuses_a_tampered_payload() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    let token = key.sign_own(&claims("POST", PATH, BODY));
    let parts: Vec<&str> = token.split('.').collect();
    let forged = with(claims("POST", PATH, BODY), "tenant_id", json!("another-tenant"));
    let tampered = format!("{}.{}.{}", parts[0], base64_url(forged.to_string().as_bytes()), parts[2]);
    assert_eq!(verifier.verify(request(Some(&bearer(&tampered)))).await, Err(VerifyError::BadSignature));
}

#[tokio::test]
async fn refuses_an_expired_token() {
    let iat = now() - 200;
    let claims = with(with(claims("POST", PATH, BODY), "iat", json!(iat)), "exp", json!(iat + 60));
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::Expired));
}

#[tokio::test]
async fn refuses_a_token_issued_in_the_future() {
    let iat = now() + 120;
    let claims = with(with(claims("POST", PATH, BODY), "iat", json!(iat)), "exp", json!(iat + 60));
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::IssuedInFuture));
}

#[tokio::test]
async fn refuses_a_token_that_lives_too_long() {
    let iat = now();
    let claims = with(with(claims("POST", PATH, BODY), "iat", json!(iat)), "exp", json!(iat + 3600));
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::LifetimeTooLong));
}

#[tokio::test]
async fn the_default_lifetime_cap_is_the_protocol_60_seconds() {
    assert_eq!(kelta_module::testing::config().max_lifetime, std::time::Duration::from_secs(60));
    let iat = now();
    let exact = with(with(claims("POST", PATH, BODY), "iat", json!(iat)), "exp", json!(iat + 60));
    assert!(verify_claims(&exact).await.is_ok(), "the platform signs for exactly 60 s");
    let longer = with(with(claims("POST", PATH, BODY), "iat", json!(iat)), "exp", json!(iat + 61));
    assert_eq!(verify_claims(&longer).await, Err(VerifyError::LifetimeTooLong));
}

#[tokio::test]
async fn refuses_another_issuer() {
    let claims = with(claims("POST", PATH, BODY), "iss", json!("https://evil.example.test/api/modules"));
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::WrongIssuer));
}

#[tokio::test]
async fn refuses_another_audience() {
    let claims = with(claims("POST", PATH, BODY), "aud", json!("other-module"));
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::WrongAudience));
}

#[tokio::test]
async fn refuses_missing_registered_claims() {
    for claim in ["exp", "iss", "aud", "sub"] {
        let refused = verify_claims(&without(claims("POST", PATH, BODY), claim)).await.unwrap_err();
        assert!(matches!(refused, VerifyError::MissingClaim(_) | VerifyError::MalformedClaims), "{claim}: {refused:?}");
    }
}

#[tokio::test]
async fn refuses_missing_protocol_claims() {
    for claim in ["tenant_id", "htm", "htu", "body_sha256", "iat", "jti"] {
        let refused = verify_claims(&without(claims("POST", PATH, BODY), claim)).await.unwrap_err();
        assert_eq!(refused, VerifyError::MalformedClaims, "{claim}");
    }
    let blank_jti = with(claims("POST", PATH, BODY), "jti", json!(""));
    assert_eq!(verify_claims(&blank_jti).await, Err(VerifyError::MissingClaim("jti".into())));
}

#[tokio::test]
async fn refuses_another_method() {
    let claims = claims("GET", PATH, BODY);
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::MethodMismatch));
}

#[tokio::test]
async fn refuses_another_url() {
    for htu in [
        format!("{BASE_URL}/kelta/v1/events"),
        format!("{BASE_URL}{PATH}?x=1"),
        format!("https://elsewhere.example.test{PATH}"),
        format!("http://module.example.test{PATH}"),
        format!("{BASE_URL}.evil{PATH}"),
    ] {
        let claims = with(claims("POST", PATH, BODY), "htu", json!(htu));
        assert_eq!(verify_claims(&claims).await, Err(VerifyError::UrlMismatch), "{htu}");
    }
}

#[tokio::test]
async fn matches_the_url_with_its_query_and_any_registered_base() {
    let key = PlatformKey::generate("k1");
    let mut config = config();
    config.base_urls = vec!["https://first.example.test".into(), "http://module.svc:8080/".into()];
    let verifier = verifier_with(config, TestJwks::with_keys(&[&key]));
    let path = "/kelta/v1/routes/me?a=1&b=%20";
    let claims = with(claims("GET", path, b""), "htu", json!(format!("http://module.svc:8080{path}")));
    let auth = bearer(&key.sign_own(&claims));
    let request = IncomingRequest { method: "GET", path_and_query: path, authorization: Some(&auth), body: b"" };
    verifier.verify(request).await.expect("second base URL, exact query");
}

#[tokio::test]
async fn refuses_another_body() {
    let claims = claims("POST", PATH, br#"{"tenantId":"other"}"#);
    assert_eq!(verify_claims(&claims).await, Err(VerifyError::BodyHashMismatch));
}

#[tokio::test]
async fn refuses_a_replayed_token() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    let auth = bearer(&key.sign_own(&claims("POST", PATH, BODY)));
    verifier.verify(request(Some(&auth))).await.expect("first use");
    assert_eq!(verifier.verify(request(Some(&auth))).await, Err(VerifyError::Replayed));
}

#[tokio::test]
async fn a_refused_token_does_not_burn_its_jti() {
    let key = PlatformKey::generate("k1");
    let verifier = verifier(TestJwks::with_keys(&[&key]));
    let auth = bearer(&key.sign_own(&claims("POST", PATH, BODY)));
    let wrong_body = IncomingRequest { method: "POST", path_and_query: PATH, authorization: Some(&auth), body: b"{}" };
    assert_eq!(verifier.verify(wrong_body).await, Err(VerifyError::BodyHashMismatch));
    verifier.verify(request(Some(&auth))).await.expect("the genuine request still verifies");
}

fn base64_url(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}
