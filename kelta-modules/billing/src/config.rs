//! Configuration, from the environment.

use std::net::SocketAddr;
use std::time::Duration;

use crate::stripe::DEFAULT_BASE_URL;

/// The module's configuration.
#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    /// `BIND_ADDRESS`, default `0.0.0.0:8080`.
    pub bind: SocketAddr,
    /// `KELTA_MODULE_ISSUER` (required): the assertion issuer, `https://<api-host>/api/modules`.
    pub issuer: String,
    /// `KELTA_MODULE_BASE_URLS` (required, comma-separated): the base URL(s) tenant admins
    /// install the module by; every assertion's `htu` starts with one of them.
    pub base_urls: Vec<String>,
    /// `KELTA_MODULE_JWKS_URL`: where to fetch the platform keys when not at the issuer (an
    /// in-cluster gateway). Default `{issuer}/.well-known/jwks.json`.
    pub jwks_url: Option<String>,
    /// `KELTA_PLATFORM_URL` (required): the platform API base the module calls back,
    /// `{url}/{tenantSlug}/api/...`.
    pub platform_url: String,
    /// `STRIPE_API_BASE`, default the public Stripe API.
    pub stripe_api_base: String,
    /// `BILLING_ENTITLEMENTS_CACHE_SECONDS`, default 0 (off), as the JAR module: a stale
    /// entitlement is worse than a slow one, and a record event reaches one replica only.
    pub entitlements_ttl: Duration,
    /// `BILLING_RULES_CACHE_SECONDS`, default 60; 0 disables the cache. Every create in the
    /// tenant asks the quota hook, so the rules are cached and invalidated by their record events;
    /// a rule change reaches a replica that missed the event within this TTL.
    pub rules_ttl: Duration,
}

impl Config {
    /// Reads the process environment.
    pub fn from_env() -> Result<Self, String> {
        Self::from_lookup(|key| std::env::var(key).ok())
    }

    /// Reads configuration through `lookup`.
    pub fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Result<Self, String> {
        let get = |key: &str| lookup(key).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
        let required = |key: &str| get(key).ok_or_else(|| format!("{key} is required"));
        let seconds = |key: &str, default: u64| match get(key) {
            None => Ok(Duration::from_secs(default)),
            Some(v) => v.parse::<u64>().map(Duration::from_secs).map_err(|_| format!("{key} must be whole seconds")),
        };
        let bind = get("BIND_ADDRESS").unwrap_or_else(|| "0.0.0.0:8080".into());
        let base_urls: Vec<String> = required("KELTA_MODULE_BASE_URLS")?
            .split(',')
            .map(str::trim)
            .filter(|u| !u.is_empty())
            .map(str::to_string)
            .collect();
        Ok(Self {
            bind: bind.parse().map_err(|_| format!("BIND_ADDRESS '{bind}' is not host:port"))?,
            issuer: required("KELTA_MODULE_ISSUER")?,
            base_urls,
            jwks_url: get("KELTA_MODULE_JWKS_URL"),
            platform_url: required("KELTA_PLATFORM_URL")?,
            stripe_api_base: get("STRIPE_API_BASE").unwrap_or_else(|| DEFAULT_BASE_URL.into()),
            entitlements_ttl: seconds("BILLING_ENTITLEMENTS_CACHE_SECONDS", 0)?,
            rules_ttl: seconds("BILLING_RULES_CACHE_SECONDS", 60)?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn lookup(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let map: HashMap<String, String> = pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        move |k| map.get(k).cloned()
    }

    const REQUIRED: [(&str, &str); 3] = [
        ("KELTA_MODULE_ISSUER", "https://platform.example.test/api/modules"),
        ("KELTA_MODULE_BASE_URLS", "http://billing.svc:8080, https://billing.example.test"),
        ("KELTA_PLATFORM_URL", "http://gateway.svc:8080"),
    ];

    #[test]
    fn reads_required_values_and_defaults() {
        let config = Config::from_lookup(lookup(&REQUIRED)).unwrap();
        assert_eq!(config.base_urls, vec!["http://billing.svc:8080", "https://billing.example.test"]);
        assert_eq!(config.bind.to_string(), "0.0.0.0:8080");
        assert_eq!(config.stripe_api_base, DEFAULT_BASE_URL);
        assert_eq!(config.entitlements_ttl, Duration::ZERO);
        assert_eq!(config.rules_ttl, Duration::from_secs(60));
        assert_eq!(config.jwks_url, None);
    }

    #[test]
    fn refuses_missing_or_bad_values() {
        for missing in REQUIRED.map(|(k, _)| k) {
            let pairs: Vec<_> = REQUIRED.iter().copied().filter(|(k, _)| *k != missing).collect();
            assert!(Config::from_lookup(lookup(&pairs)).unwrap_err().contains(missing));
        }
        let mut pairs = REQUIRED.to_vec();
        pairs.push(("BILLING_RULES_CACHE_SECONDS", "soon"));
        assert!(Config::from_lookup(lookup(&pairs)).is_err());
        let mut pairs = REQUIRED.to_vec();
        pairs.push(("BIND_ADDRESS", "nowhere"));
        assert!(Config::from_lookup(lookup(&pairs)).is_err());
    }
}
