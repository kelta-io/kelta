# Slice 6 — Distribution: Binaries, Downloads Service, Self-Update, Pipeline

> **Status:** partially shipped — `kelta update [--check]` / `kelta version` (`kelta-web/packages/cli/src/commands/update.ts`), `src/update/{manifest,selfUpdate,passiveCheck}.ts`, binaries via `kelta-web/scripts/build-binaries.mjs`, `kelta-cli-downloads/` (Dockerfile, nginx.conf, install.sh, install.ps1), `cli-downloads` matrix entry + in-cluster smoke in `build-and-publish-containers.yml`; `https://downloads.kelta.io/cli/manifest.json` is live. See [Open](#open). Verified against main 2026-10-09.

> Child of `specs/kelta-cli/README.md`, decisions D1 + D5. Ships `kelta` as self-updating
> single-file binaries served from the cluster, published on every merge to main.

## 1. Goal & scope

- **Binary builds**: `bun build --compile` for `bun-linux-x64`, `bun-linux-arm64`,
  `bun-darwin-x64`, `bun-darwin-arm64`, `bun-windows-x64` — all from the linux
  `k8s-runner`. Version + git sha embedded via build-time defines; scheme
  `MAJOR.MINOR.<run_number>` (MAJOR.MINOR from `package.json`, patch = `github.run_number`
  → monotonic without manual tags).
- **`emf-cli-downloads` image**: `nginx:1.25-alpine` (kelta-ui pattern: `/health`, gzip
  off for binaries, immutable cache for `/cli/releases/*`, short cache for
  `/cli/manifest.json`). Layout:
  - `/cli/manifest.json` — `{ manifestVersion: 1, version, gitSha, builtAt,
    targets: { "<os>-<arch>": { url, sha256, size } } }`
  - `/cli/releases/<version>/kelta-<os>-<arch>[.exe]`
  - `/cli/install.sh`, `/cli/install.ps1` — detect platform, download, verify sha256,
    place in `~/.local/bin` / `%LOCALAPPDATA%\kelta\bin`, print PATH hint.
- **Pipeline** (`build-and-publish-containers.yml`): add `cli` to the paths filter
  (`kelta-web/packages/{cli,sdk}/**`) and a `build-cli` job on `k8s-runner` (pinned
  `BUN_VERSION` env beside `JAVA_VERSION`): install → test → compile 5 targets → generate
  manifest → docker build/push `harbor.rzware.com/emf/emf-cli-downloads:{latest,main-<sha>}`
  → included in the existing `deploy` kustomize bump (same Harbor tag-exists guard).
  Smoke step: curl manifest, download linux-x64 binary, run `kelta version --output json`,
  assert version/sha match the build.
- **Self-update** (`src/update/`):
  - `kelta update [--check]` — fetch manifest from embedded default
    `https://downloads.kelta.io/cli` (override: `KELTA_UPDATE_URL`, profile setting);
    compare semver; download own target; verify sha256; atomic swap — POSIX: write
    `kelta.new` beside current binary, chmod, `rename(2)`; Windows: rename running exe to
    `kelta.exe.old`, move new into place, delete `.old` on next run.
  - Passive check: at most once/24h (timestamp in `~/.kelta/update-check.json`), TTY +
    stderr only, prints "new version x.y.z available — run `kelta update`". **Never**
    auto-replaces mid-command. Disable: `KELTA_UPDATE_CHECK=0` or config — install docs
    tell CI users to set it.
- **External track (homelab-argo)**: Deployment + Service + Ingress `downloads.kelta.io`
  (cluster-issuer TLS like other hosts), kustomize image entry. Not in this repo; PR
  choreographed with this slice.

Out of scope: GitHub Releases, npm publish, signing/notarization (parent non-goals; risks
below), delta updates, release channels beyond `stable` (manifest layout leaves room:
`/cli/channels/<name>/manifest.json` later).

## 2. UI samples

```console
$ curl -fsSL https://downloads.kelta.io/cli/install.sh | sh
kelta 1.0.412 installed to ~/.local/bin/kelta

$ kelta update
✔ 1.0.412 → 1.0.418 (sha256 verified)
```

## 3. Data & API contracts

`manifest.json` schema above is the update contract — versioned, additive-only. The
`version` field must always be comparable semver; CI fails the build if not.

## 4. DB migrations

N/A.

## 5. File-by-file code changes

- `kelta-web/packages/cli/`: `src/update/{manifest,selfUpdate,passiveCheck}.ts`,
  `src/commands/{update,version}.ts`, `scripts/build-binaries.ts`,
  `scripts/gen-manifest.ts`.
- New `kelta-cli-downloads/`: `Dockerfile`, `nginx.conf`, `install.sh`, `install.ps1`
  (top-level dir, matching per-service layout).
- `.github/workflows/build-and-publish-containers.yml`: paths filter, `build-cli` job,
  matrix entry `cli-downloads`, smoke addition.

## 6. Test plan

- Unit: semver compare, manifest schema validation, atomic-swap path logic (mocked fs) for
  POSIX + Windows branches, passive-check throttle.
- CI-native: the pipeline smoke IS the integration test (download + run + version assert).
- Manual once per OS at first release: install script on macOS (arm64), Windows 11 x64,
  Linux — checklist in the PR; macOS Gatekeeper behavior recorded (see risks).
- e2e guard: `kelta version` and `kelta update --check` against the compose-served
  downloads container.

## 7. Docs to update

- `ci-cd.md` (new job + image + smoke); `CLAUDE.md` Stack table (Bun pin) + Module Map
  (`kelta-cli-downloads`); `README.md` install section; `status.md`.

## 8. Risks & open questions

- **Bun compile compatibility** is the load-bearing assumption of D1 — prove it in a spike
  commit FIRST (compile current CLI + run `records list` against compose on all 5 targets;
  linux natively, mac/windows manually). Plan-B: Node SEA; plan-C: Go rewrite of a thin
  core (explicitly last resort).
- Unsigned binaries: macOS quarantines browser downloads, but `curl | sh` and self-update
  don't set the quarantine attribute — acceptable internally; signing/notarization is a
  prerequisite for any public distribution and is tracked, not scheduled.
- Windows swap dance leaves `kelta.exe.old` if the process dies mid-update — next-run
  cleanup handles it; document in troubleshooting.
- Binary size (~60–90 MB × 5 per merge) grows Harbor storage — retention policy for
  `emf-cli-downloads` tags (keep last N) noted for homelab-argo housekeeping.
- Runner arch is linux/amd64 — bun cross-compile needs no QEMU, but the docker image build
  is amd64-only like every other service (fine: nginx serves static bytes).

## Open

Promised above but absent from `kelta-web/packages/cli/` on main (2026-10-09):

- **Config-file update settings** — the update URL override and the passive-check opt-out
  are env-only (`KELTA_UPDATE_URL` in `src/update/manifest.ts`, `KELTA_UPDATE_CHECK=0` in
  `src/update/passiveCheck.ts`); `ConfigFile` in `src/config/store.ts` has no update
  settings, so the "profile setting" / "or config" paths in §1 do not exist.
