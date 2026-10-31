//! The `kelta-billing` service.

use std::process::ExitCode;
use std::sync::Arc;

use kelta_billing::cache::Caches;
use kelta_billing::config::Config;
use kelta_billing::manifest::MODULE_ID;
use kelta_billing::stripe::StripeClient;
use kelta_billing::{Billing, FromCall, service};
use kelta_module::server::serve;
use kelta_module::{AssertionVerifier, VerifierConfig};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> ExitCode {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    if std::env::var("LOG_FORMAT").is_ok_and(|f| f == "text") {
        tracing_subscriber::fmt().with_env_filter(filter).init();
    } else {
        tracing_subscriber::fmt().json().with_env_filter(filter).with_current_span(false).init();
    }
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!(error = %e, "kelta-billing stopped");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), String> {
    let config = Config::from_env()?;
    let mut verifier = VerifierConfig::new(&config.issuer, MODULE_ID, config.base_urls.clone());
    verifier.jwks_url = config.jwks_url.clone();
    let verifier = AssertionVerifier::new(verifier).map_err(|e| e.to_string())?;
    tracing::info!(
        version = env!("CARGO_PKG_VERSION"),
        issuer = %config.issuer,
        jwks = %verifier.config().resolved_jwks_url(),
        base_urls = ?config.base_urls,
        "starting kelta-billing"
    );
    let billing = Billing::new(
        FromCall,
        Caches::new(config.entitlements_ttl, config.rules_ttl),
        StripeClient::new(&config.stripe_api_base),
    );
    let router =
        service(Arc::new(verifier), billing).map_err(|e| e.to_string())?.platform_url(&config.platform_url).router();
    serve(router, config.bind).await.map_err(|e| e.to_string())
}
