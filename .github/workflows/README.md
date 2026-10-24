# GitHub Workflows

The pipeline is documented in [`.claude/docs/ci-cd.md`](../../.claude/docs/ci-cd.md): PR CI
(`ci.yml`), the post-merge Harbor build + homelab-argo deploy (`build-and-publish-containers.yml`),
tagged releases to `ghcr.io/kelta-io` (`release.yml`; the release-to-HEAD upgrade test is the
`upgrade` job in `ci.yml`), auto-merge, DORA metrics, the runner image and CodeQL code scanning (`codeql.yml`; Dependabot
version updates are configured in `../dependabot.yml`). Shared CI database scripts
are described in [`scripts/ci/README.md`](../../scripts/ci/README.md).

Update `ci-cd.md`, not this file, when a workflow changes.
