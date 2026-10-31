//! `kelta-module`: an SDK for external Kelta modules (module protocol v1).
//!
//! An external module is an HTTP service the platform calls with a signed assertion. This crate
//! gives a module what every one needs:
//!
//! - [`assertion`]: verify the platform's ES256 assertion on every request — JWKS fetched and
//!   cached, `iss`/`aud`/`exp`/`iat`, `htm`/`htu` against the actual request, `body_sha256`
//!   against the raw body, and `jti` replay;
//! - [`server`]: the protocol endpoints as an axum router that verifies before any handler runs,
//!   with [`server::ModuleHandlers`] for the module's logic;
//! - [`protocol`]: the typed request and response bodies;
//! - [`client`]: the platform API and module credentials, called with the `Kelta-Access-Token`;
//! - [`manifest`]: a manifest builder that checks what the installer would refuse.
//!
//! Identity — tenant, install, acting user — comes only from the verified claims
//! ([`server::Call`]). Forwarded webhook headers are attacker-controlled
//! ([`server::UntrustedHeaders`]); no API here reads identity from a header.

pub mod assertion;
pub mod client;
pub mod manifest;
pub mod protocol;
pub mod server;
#[cfg(feature = "testing")]
pub mod testing;

pub use assertion::{AssertionVerifier, VerifiedAssertion, VerifierConfig, VerifyError};
pub use client::{ClientError, Platform, PlatformClient, Query, Resource};
pub use manifest::Manifest;
pub use server::{Call, HandlerError, ModuleHandlers, ModuleService};
