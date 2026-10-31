//! Short-lived per-tenant caches of resolved entitlements and quota rules.
//!
//! The quota hook runs on every record create of the tenant, and the platform's entitlements port
//! is called with a cache TTL of 0, so both reads happen here. Entries expire after their TTL and
//! are dropped early by this module's own writes and by record events on the billing collections.
//! With several replicas, an event reaches one of them: the others serve an entry until its TTL,
//! so keep the TTL short (or 0 to disable).
//!
//! A load that started before an invalidation is not stored: each tenant carries a generation
//! that every invalidation bumps.

use std::collections::HashMap;
use std::future::Future;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use kelta_module::client::{ClientError, Resource};
use kelta_module::protocol::MemberEntitlements;

/// Entries above which a cache drops its expired entries before inserting.
const PRUNE_ABOVE: usize = 10_000;

struct Entry<T> {
    at: Instant,
    value: T,
}

#[derive(Default)]
struct State {
    generations: HashMap<String, u64>,
    entitlements: HashMap<(String, String), Entry<MemberEntitlements>>,
    rules: HashMap<String, Entry<Arc<Vec<Resource>>>>,
}

/// The caches of one module process.
pub struct Caches {
    entitlements_ttl: Duration,
    rules_ttl: Duration,
    state: Mutex<State>,
}

impl Caches {
    pub fn new(entitlements_ttl: Duration, rules_ttl: Duration) -> Self {
        Self { entitlements_ttl, rules_ttl, state: Mutex::new(State::default()) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn generation(&self, tenant: &str) -> u64 {
        self.lock().generations.get(tenant).copied().unwrap_or_default()
    }

    /// `user`'s entitlements in `tenant`, from the cache or `load`.
    pub async fn entitlements<F>(&self, tenant: &str, user: &str, load: F) -> Result<MemberEntitlements, ClientError>
    where
        F: Future<Output = Result<MemberEntitlements, ClientError>>,
    {
        let key = (tenant.to_string(), user.to_string());
        if let Some(entry) = self.lock().entitlements.get(&key)
            && entry.at.elapsed() < self.entitlements_ttl
        {
            return Ok(entry.value.clone());
        }
        let generation = self.generation(tenant);
        let value = load.await?;
        if !self.entitlements_ttl.is_zero() {
            let mut state = self.lock();
            if state.generations.get(tenant).copied().unwrap_or_default() == generation {
                if state.entitlements.len() > PRUNE_ABOVE {
                    let ttl = self.entitlements_ttl;
                    state.entitlements.retain(|_, e| e.at.elapsed() < ttl);
                }
                state.entitlements.insert(key, Entry { at: Instant::now(), value: value.clone() });
            }
        }
        Ok(value)
    }

    /// `tenant`'s active quota rules, from the cache or `load`.
    pub async fn rules<F>(&self, tenant: &str, load: F) -> Result<Arc<Vec<Resource>>, ClientError>
    where
        F: Future<Output = Result<Vec<Resource>, ClientError>>,
    {
        if let Some(entry) = self.lock().rules.get(tenant)
            && entry.at.elapsed() < self.rules_ttl
        {
            return Ok(entry.value.clone());
        }
        let generation = self.generation(tenant);
        let value = Arc::new(load.await?);
        if !self.rules_ttl.is_zero() {
            let mut state = self.lock();
            if state.generations.get(tenant).copied().unwrap_or_default() == generation {
                if state.rules.len() > PRUNE_ABOVE {
                    let ttl = self.rules_ttl;
                    state.rules.retain(|_, e| e.at.elapsed() < ttl);
                }
                state.rules.insert(tenant.to_string(), Entry { at: Instant::now(), value: value.clone() });
            }
        }
        Ok(value)
    }

    /// Drops one member's entitlements.
    pub fn invalidate_member(&self, tenant: &str, user: &str) {
        let mut state = self.lock();
        *state.generations.entry(tenant.to_string()).or_default() += 1;
        state.entitlements.remove(&(tenant.to_string(), user.to_string()));
    }

    /// Drops every member's entitlements of `tenant` (a plan changed).
    pub fn invalidate_tenant_entitlements(&self, tenant: &str) {
        let mut state = self.lock();
        *state.generations.entry(tenant.to_string()).or_default() += 1;
        state.entitlements.retain(|(t, _), _| t != tenant);
    }

    /// Drops `tenant`'s quota rules.
    pub fn invalidate_rules(&self, tenant: &str) {
        let mut state = self.lock();
        *state.generations.entry(tenant.to_string()).or_default() += 1;
        state.rules.remove(tenant);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn plan(code: &str) -> MemberEntitlements {
        MemberEntitlements { plan_code: Some(code.into()), ..Default::default() }
    }

    #[tokio::test]
    async fn caches_until_invalidated() {
        let caches = Caches::new(Duration::from_secs(60), Duration::from_secs(60));
        let loads = AtomicUsize::new(0);
        let load = || async {
            loads.fetch_add(1, Ordering::SeqCst);
            Ok(plan("pro"))
        };
        caches.entitlements("t", "u", load()).await.unwrap();
        caches.entitlements("t", "u", load()).await.unwrap();
        assert_eq!(loads.load(Ordering::SeqCst), 1);
        caches.invalidate_member("t", "u");
        caches.entitlements("t", "u", load()).await.unwrap();
        assert_eq!(loads.load(Ordering::SeqCst), 2);
        caches.invalidate_tenant_entitlements("t");
        caches.entitlements("t", "u", load()).await.unwrap();
        assert_eq!(loads.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn a_zero_ttl_never_caches() {
        let caches = Caches::new(Duration::ZERO, Duration::ZERO);
        let loads = AtomicUsize::new(0);
        for _ in 0..3 {
            caches
                .rules("t", async {
                    loads.fetch_add(1, Ordering::SeqCst);
                    Ok(Vec::new())
                })
                .await
                .unwrap();
        }
        assert_eq!(loads.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn a_load_racing_an_invalidation_is_not_stored() {
        let caches = Caches::new(Duration::from_secs(60), Duration::from_secs(60));
        let stale = caches
            .entitlements("t", "u", async {
                // A webhook write lands while this load is in flight.
                caches.invalidate_member("t", "u");
                Ok(plan("free"))
            })
            .await
            .unwrap();
        assert_eq!(stale.plan_code.as_deref(), Some("free"));
        let fresh = caches.entitlements("t", "u", async { Ok(plan("pro")) }).await.unwrap();
        assert_eq!(fresh.plan_code.as_deref(), Some("pro"));
    }

    #[tokio::test]
    async fn errors_are_not_cached() {
        let caches = Caches::new(Duration::from_secs(60), Duration::from_secs(60));
        let failed = caches.entitlements("t", "u", async { Err(ClientError::Transport("down".into())) }).await;
        assert!(failed.is_err());
        assert!(caches.entitlements("t", "u", async { Ok(plan("pro")) }).await.is_ok());
    }
}
