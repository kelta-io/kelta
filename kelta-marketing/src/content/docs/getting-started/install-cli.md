---
title: Install the Kelta CLI
description: Install the self-updating kelta binary on macOS, Linux or Windows, verify it, and log in.
section: getting-started
order: 40
---

`kelta` is a single self-updating binary. It covers the whole admin surface (collections, fields, layouts, list
views, pages, menus, flows, users, sandboxes, promotion…), record CRUD, a raw `kelta api` escape hatch, and a local
MCP bridge. Output is JSON when piped, so it doubles as the scripting and agent interface.

## macOS and Linux

```bash
curl -fsSL https://downloads.kelta.io/cli/install.sh | sh
```

The script detects `darwin`/`linux` and `x64`/`arm64`, downloads the latest release, verifies it against the
release's `SHA256SUMS`, and installs to `~/.local/bin/kelta`. If that directory is not on your `PATH` the script
tells you what to add.

| Variable | Effect |
|---|---|
| `KELTA_INSTALL_DIR` | Install directory (default `~/.local/bin`) |
| `KELTA_DOWNLOADS_URL` | Base URL of the download service (default `https://downloads.kelta.io`) — for mirrors |

The binaries are not code-signed. `curl | sh` and the self-updater do not set the macOS quarantine attribute, so
Gatekeeper does not intervene; if you download a binary through a browser instead, you may need to allow it in
*System Settings → Privacy & Security*.

## Windows

```powershell
irm https://downloads.kelta.io/cli/install.ps1 | iex
```

The Windows build is produced with the same pipeline but has had less real-world testing than the macOS and Linux
builds.

## Supported targets

`darwin-x64`, `darwin-arm64`, `linux-x64`, `linux-arm64`, `windows-x64`.

## Verify and update

```bash
kelta version          # installed version and build target
kelta update --check   # is a newer release available?
kelta update           # download, sha256-verify and replace the binary in place
```

| Variable | Effect |
|---|---|
| `KELTA_UPDATE_URL` | Override where `kelta update` looks for releases |
| `KELTA_UPDATE_CHECK=0` | Disable the background "new version available" notice |

## Mirroring for air-gapped installs

The download service is a static file tree, so it is trivial to mirror:

```
/cli/latest.txt                       version string, e.g. 1.0.123
/cli/manifest.json                    versions, targets, checksums
/cli/releases/<version>/kelta-<target>
/cli/releases/<version>/SHA256SUMS
/cli/install.sh, /cli/install.ps1
```

Point `KELTA_DOWNLOADS_URL` (install) and `KELTA_UPDATE_URL` (update) at your mirror.

## Log in

```bash
kelta auth login --url https://api.example.com --tenant acme
```

This opens your browser, completes an OAuth authorization-code flow with PKCE against the tenant's auth server
(MFA and SSO work exactly as in the app), and stores a 90-day personal access token in a profile under `~/.kelta/`.
For headless machines, create a token in the app under **Profile → API tokens** and pass it directly:

```bash
kelta auth login --url https://api.example.com --tenant acme --token klt_...
```

For CI, no profile is needed at all — set `KELTA_URL`, `KELTA_TENANT` and `KELTA_TOKEN` in the environment.

```bash
kelta auth status      # what the active profile resolves to
kelta collections list # first real call
```

Continue with the [CLI overview](/docs/cli/overview/) and [Authentication and profiles](/docs/cli/auth-and-profiles/).
