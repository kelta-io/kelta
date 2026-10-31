# kelta-module — Rust SDK for external Kelta modules

An **external module** is an HTTP service that the platform calls with a signed assertion. A
tenant admin installs it by URL (`POST /api/modules/plan` → `POST /api/modules/install`). This
crate holds what every such service needs, so a module only writes its own logic. The contract
it implements is [`docs/module-protocol/openapi.yaml`](../../docs/module-protocol/openapi.yaml).
The reference module built on it is [`../billing`](../billing/).

License: **MIT** (the same as the `@kelta/*` SDK packages in `kelta-web`).

## What it gives you

| Module | Does |
|---|---|
| `assertion` | Verifies the platform's ES256 assertion on every request (see below). |
| `server` | The protocol endpoints as an axum `Router`. It verifies before any handler runs; the module implements `ModuleHandlers`. |
| `protocol` | Typed request and response bodies: actions, hooks, events, entitlements, lifecycle, health, credentials. |
| `client` | The platform API (`PlatformClient`), called with the request's `Kelta-Access-Token`; `credential(name, purpose)` reads a declared tenant credential. |
| `manifest` | A manifest v2 builder. `build()` refuses what the installer would refuse. |
| `testing` (feature) | A signing key, signed test assertions and an in-memory platform. Enable it in `dev-dependencies` only. |

## Identity comes from signed claims only

`Call` is what a handler gets. The tenant (`tenant_id`, `tenant_slug`), the install
(`install_id`) and the acting user (`actor`) all come from the **verified** assertion claims.

Headers that a third party forwards through the platform, such as a webhook provider's
signature headers, arrive as `UntrustedHeaders`. The sender controls them, including
`X-User-Id`, `X-Tenant-ID` and anything else that looks like identity. **No API in this crate
reads identity from a header, and a module must not add one.** Use those headers only for what
the provider documents, such as `Stripe-Signature`.

## What the verifier checks

For every call under `/kelta/v1/*`, before the handler runs:

| Check | Refused when |
|---|---|
| Signature | not ES256, or the key id (`kid`) is not in the platform's JWKS |
| `iss` | not exactly the configured issuer |
| `aud` | not exactly the module id |
| `exp` / `iat` | expired, issued in the future, or `exp - iat` over the lifetime cap, with 30 s skew allowed |
| `htm` / `htu` | the method, or the base URL + path + query, differs from the request actually received |
| `body_sha256` | the hex SHA-256 of the **raw** body differs |
| `jti` | seen before while the assertion is still valid (replay) |

The JWKS comes from `{issuer}/.well-known/jwks.json`, or from `VerifierConfig::jwks_url` when the
module reaches the platform by another route, such as an in-cluster gateway (`iss` is still
checked against the issuer). It is cached for 10 minutes. An unknown `kid` refetches it, at most
once every 10 seconds, so a rotated platform key is picked up without a restart.

Every refusal is `401` with a generic body. The reason is logged at `debug` and never returned.
When the JWKS cannot be fetched and no cached key matches, the answer is `503`, so the platform
retries instead of treating the module as refusing it.
Tests cover each refusal: `tests/assertion.rs` and `tests/server.rs`.

`GET /.well-known/kelta-module.json` (the manifest) and declared static assets, such as the UI
bundle, are the only unauthenticated paths.

## Minimal module

```rust
use std::sync::Arc;
use kelta_module::{AssertionVerifier, Call, HandlerError, Manifest, ModuleHandlers, ModuleService, VerifierConfig};
use kelta_module::manifest::Action;
use kelta_module::protocol::{ActionRequest, ActionResponse};

struct Hello;

impl ModuleHandlers for Hello {
    async fn action(&self, call: Call, key: String, _request: ActionRequest) -> Result<ActionResponse, HandlerError> {
        let mut output = serde_json::Map::new();
        output.insert("tenant".into(), call.tenant_id().into());
        output.insert("action".into(), key.into());
        Ok(ActionResponse::success(output))
    }
}

#[tokio::main]
async fn main() {
    let manifest = Manifest::new("hello", "Hello", "1.0.0")
        .action(Action::new("greet", "Greet"))
        .build()
        .expect("manifest");
    let verifier = AssertionVerifier::new(VerifierConfig::new(
        "https://api.example.test/api/modules",
        "hello",
        vec!["https://hello.example.test".into()],
    ))
    .expect("verifier");
    let router = ModuleService::new(Arc::new(verifier), &manifest, Hello)
        .platform_url("https://api.example.test")
        .router();
    kelta_module::server::serve(router, "0.0.0.0:8080".parse().unwrap()).await.unwrap();
}
```

Every `ModuleHandlers` method has a default, so a module implements only what its manifest
declares. Actions, entitlements, routes and webhooks default to `404`. Hooks default to allow.
Events and lifecycle calls default to a no-op acknowledgement. Health defaults to `ok`.

## Calling the platform

`call.platform()` returns a `PlatformClient` bound to the request's tenant slug and
`Kelta-Access-Token`. That token belongs to the module's **service user**, whose profile holds
exactly the permissions the admin approved. It is valid only for the request that carried it:
never store it.

`credential(name, purpose)` reads `GET /api/modules/self/credentials/{name}`. The manifest must
declare the name in `permissions.credentials`. Keep the secret in memory for that request only,
and never log it or return it.

## Logging

The router logs one `debug` line per request on the target `kelta_module::access`: method,
path, status and `elapsed_us`. It never logs the query, headers or body. Enable it with
`RUST_LOG=info,kelta_module=debug`.

## Build and test

From `kelta-modules/`:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
```

CI runs the same commands as the `check` target of `kelta-modules/Dockerfile` (job
`test-rust-modules`).
