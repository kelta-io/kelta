# scripts/ci

Helpers for using the **`kelta-ci-db` PostgreSQL pool** from GitHub Actions
and other CI contexts. Pool lives in the homelab cluster as 4 PG StatefulSet
replicas pinned to node `fc17` — see [`homelab-argo/kelta-ci-db/`](https://github.com/RZWare/homelab-argo/tree/main/kelta-ci-db).

## Lifecycle

```
                          eval "$(checkout-db.sh)"
                                      │
   ┌──────────────────────────────────▼────────────────────────────┐
   │  CI step runs against $CI_DB_JDBC_URL (single PG schema,      │
   │  isolated from sibling jobs sharing the same instance)        │
   └──────────────────────────────────┬────────────────────────────┘
                                      │
                          release-db.sh  (drops schema, CASCADE)
```

`checkout-db.sh` picks an instance ordinal (random by default), creates a
unique schema named `ci_<run-tag>`, and emits `export` lines for sourcing.
It also writes `/tmp/ci-db-checkout.env` so `release-db.sh` knows what to
drop later.

## Usage in a workflow

```yaml
- name: Check out CI DB schema
  run: |
    eval "$(scripts/ci/checkout-db.sh)"
    echo "CI_DB_JDBC_URL=$CI_DB_JDBC_URL" >> $GITHUB_ENV
    echo "CI_DB_USER=$CI_DB_USER"         >> $GITHUB_ENV
    echo "CI_DB_PASSWORD=$CI_DB_PASSWORD" >> $GITHUB_ENV

- name: Run integration tests
  env:
    SPRING_DATASOURCE_URL: ${{ env.CI_DB_JDBC_URL }}
    SPRING_DATASOURCE_USERNAME: ${{ env.CI_DB_USER }}
    SPRING_DATASOURCE_PASSWORD: ${{ env.CI_DB_PASSWORD }}
  run: mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests -B

- name: Release CI DB schema
  if: always()
  run: scripts/ci/release-db.sh
```

The `if: always()` on release ensures even a failed test cleans up its
schema. Pool drift is bounded by per-schema cleanup, not per-instance reset.

## Environment overrides

| Var | Default | Purpose |
|-----|---------|---------|
| `CI_DB_NAMESPACE` | `kelta-ci-db` | K8s namespace of the pool |
| `CI_DB_SVC` | `kelta-ci-db` | Headless service name |
| `CI_DB_USER` | `ci` | PG role |
| `CI_DB_DATABASE` | `ci` | PG database name |
| `CI_DB_PASSWORD` | (fetched from secret) | Override to skip `kubectl get secret` |
| `CI_DB_INSTANCE` | random | Pin to a specific instance ordinal `0..N-1` |
| `CI_DB_POOL_SIZE` | `4` | Must match the StatefulSet replica count |

## Prerequisites on the runner

- `kubectl` with permission to read the `kelta-ci-db-credentials` secret in
  the `kelta-ci-db` namespace
- `psql` (PostgreSQL client)
- Network reachability to the pool DNS:
  `kelta-ci-db-N.kelta-ci-db.kelta-ci-db.svc.cluster.local`

The `k8s-runner-integration` pool already has `kubectl` and runs inside the
cluster, so DNS works directly. For external runners add a sidecar that
exposes the pool via NodePort or LoadBalancer.

## What's NOT included

- The Java test harness (`kelta-test-harness`) currently uses Testcontainers
  to boot its own PG. Migrating the harness to read `CI_DB_JDBC_URL` from
  env (and skip Testcontainers when set) is a follow-up task. The pool is
  ready; the integration is the next PR.
