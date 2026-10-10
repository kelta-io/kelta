# Upgrading Kelta

This covers a Docker Compose install started from the [Quickstart](README.md#quickstart): the
stack in `docker-compose.yml`, running the release named by `KELTA_VERSION` in `.env`. An upgrade
moves that one value to a newer release, pulls its images and restarts the stack on the same
volumes. Your data stays in the `postgres_data` volume; the new kelta-worker migrates its
schema when it starts.

Upgrade one release at a time, and read that release's notes first.

## Before you start

**Read the release notes.** Every release tag (`v1.2.3`) has a GitHub Release at
`https://github.com/kelta-io/kelta/releases/tag/v<version>`, with notes generated from the
release's conventional commits (`.github/workflows/release.yml`). Breaking changes and any manual
steps are listed there, not in this file.

**Back up the postgres volume.** Database migrations only go forward. Flyway runs no down
migrations, so the backup is your only way back. Stop the stack so the files on disk are
consistent, then copy the volume into a tarball:

```bash
docker compose stop
docker volume ls --filter name=postgres_data      # e.g. kelta_postgres_data (<project>_postgres_data)
docker run --rm -v kelta_postgres_data:/data:ro -v "$PWD":/backup alpine \
  tar czf /backup/postgres_data-$(date +%Y%m%d-%H%M%S).tgz -C /data .
```

Swap in your volume name if it differs. The project name defaults to the directory you cloned
into. Keep the tarball somewhere other than the Docker host if the host is the thing you are
protecting against.

If you also want a logical dump (portable across Postgres patch versions, but not a substitute
for the volume copy above), take it while postgres is running:

```bash
docker compose start postgres
docker compose exec -T postgres pg_dump -U kelta -Fc kelta_control_plane > kelta-$(date +%Y%m%d).dump
```

## Upgrade

1. **Bump `KELTA_VERSION` in `.env`** to the release you are moving to, without the `v`:

   ```bash
   # .env
   KELTA_VERSION=0.2.0
   ```

2. **Pull the new images:**

   ```bash
   docker compose pull
   ```

3. **Start the stack:**

   ```bash
   docker compose up -d
   ```

   Add `--wait` to block until every service reports healthy.

4. **Migrations run on kelta-worker boot.** The worker applies every new Flyway migration
   (`kelta-worker/src/main/resources/db/migration/`) to the existing database before it reports
   healthy, and auth and gateway wait for a healthy worker. Follow it with:

   ```bash
   docker compose logs -f kelta-worker      # look for "Successfully applied N migrations"
   ```

   If a migration fails, the worker does not become healthy and the stack does not come up. Its
   log names the failing migration. Do not edit the database by hand to get past it: restore the
   backup (below) and report the failure with that log.

5. **Check your data.** Sign in, open a collection you use, and confirm its records are there.

## Rolling back

Flyway does not run down-migrations, so going back to an older `KELTA_VERSION` on a database
the newer release has already migrated is not supported. Rolling back means restoring the backup
you took before the upgrade:

```bash
docker compose down                       # keeps volumes; do NOT add -v until the backup is verified
# set KELTA_VERSION in .env back to the version the backup was taken on
docker run --rm -v kelta_postgres_data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -rf /data/* /data/..?* /data/.[!.]* 2>/dev/null; tar xzf /backup/postgres_data-<timestamp>.tgz -C /data'
docker compose up -d
```

Anything written after the backup was taken is lost.

## What CI checks

Every pull request that touches a migration, a compose file, a Dockerfile or the upgrade job
itself runs the **Upgrade Test** workflow (`.github/workflows/upgrade-test.yml`). It boots the
newest release reachable from the PR, seeds a tenant, a collection, records and a user through
the API, stops the stack, starts the PR's build on the same volumes, and fails if any seeded
value reads back differently. It covers one release to the next. Skipping releases is not tested.
