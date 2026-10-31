# syntax=docker/dockerfile:1.7
#
# The Rust external modules: the kelta-module SDK crate and the kelta-billing service
# (kelta-modules/Cargo.toml workspace). Build context: the REPOSITORY ROOT, like every other
# image (Dockerfile.dockerignore next to this file allowlists kelta-modules/ only).
#
# Targets:
#   check    cargo fmt --check, clippy -D warnings and the whole test suite. CI's
#            "Test Rust Modules" job builds this target; nothing is pushed.
#   runtime  (default) the kelta-billing image Build and Deploy pushes to Harbor: one static
#            musl binary on distroless/static, non-root, no shell.
#
# Each target compiles the dependency graph against stub sources first, so a source-only
# change reuses the cached dependency layer instead of rebuilding ~200 crates.
#
#   docker build -f kelta-modules/Dockerfile -t kelta-billing .
#   docker build -f kelta-modules/Dockerfile --target check .

ARG RUST_IMAGE=rust:1.99.1-slim-bookworm
ARG RUNTIME_IMAGE=gcr.io/distroless/static-debian12:nonroot

FROM ${RUST_IMAGE} AS toolchain
# musl-tools: the C compiler ring needs for the static musl target.
RUN apt-get update \
 && apt-get install -y --no-install-recommends musl-tools \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# rust-toolchain.toml pins the channel and components; rustup installs them on first use.
COPY kelta-modules/rust-toolchain.toml ./
RUN rustup show active-toolchain \
 && rustup target add x86_64-unknown-linux-musl
ENV CARGO_TERM_COLOR=always

# The manifests, the lockfile and empty crate roots: enough for cargo to resolve and build every
# dependency without the sources.
FROM toolchain AS skeleton
COPY kelta-modules/Cargo.toml kelta-modules/Cargo.lock kelta-modules/rustfmt.toml ./
COPY kelta-modules/sdk-rust/Cargo.toml sdk-rust/
COPY kelta-modules/billing/Cargo.toml billing/
RUN mkdir -p sdk-rust/src billing/src \
 && touch sdk-rust/src/lib.rs billing/src/lib.rs \
 && echo 'fn main() {}' > billing/src/main.rs

# ── check ────────────────────────────────────────────────────────────────────────────────
FROM skeleton AS check-deps
RUN cargo clippy --workspace --all-targets --locked \
 && cargo test --workspace --locked --no-run

FROM check-deps AS check
COPY kelta-modules/ ./
# COPY keeps the context's mtimes, which can predate the stub build; touch them so cargo
# rebuilds the workspace crates rather than trusting the stubs' fingerprints.
RUN find sdk-rust billing -name '*.rs' -exec touch {} + \
 && cargo fmt --all --check \
 && cargo clippy --workspace --all-targets --locked -- -D warnings \
 && cargo test --workspace --locked

# ── runtime ──────────────────────────────────────────────────────────────────────────────
FROM skeleton AS build-deps
RUN cargo build --release --locked --target x86_64-unknown-linux-musl -p kelta-billing

FROM build-deps AS build
COPY kelta-modules/ ./
RUN find sdk-rust billing -name '*.rs' -exec touch {} + \
 && cargo build --release --locked --target x86_64-unknown-linux-musl -p kelta-billing \
 && cp target/x86_64-unknown-linux-musl/release/kelta-billing /kelta-billing

FROM ${RUNTIME_IMAGE} AS runtime
COPY --from=build /kelta-billing /kelta-billing
USER nonroot:nonroot
EXPOSE 8080
# Probes: GET /.well-known/kelta-module.json is the one unauthenticated endpoint (every
# /kelta/v1/* call needs a platform assertion), and it answers only once the service is up.
ENTRYPOINT ["/kelta-billing"]
