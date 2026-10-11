// CLI for .github/workflows/release.yml. Usage (Node >= 24):
//   node .github/release/release.ts plan                    → GITHUB_OUTPUT: publish, tag, version, prerelease, latest
//   node .github/release/release.ts check-images <build.json> <base.json>  → GITHUB_OUTPUT: images; exit 1 on drift
//     build.json = docker-compose.yml + docker-compose.build.yml + docker-compose.jvm.yml (what the release builds)
//     base.json  = docker-compose.yml alone (what the quickstart pulls)
//   node .github/release/release.ts notes <ref> <out.md>    → release notes + added migrations since the previous v* tag
//   node .github/release/release.ts check-pin               → exit 1 when .env.example and docker-compose.yml pin different versions
//   node .github/release/release.ts pin <version>           → rewrite the quickstart pin in .env.example and docker-compose.yml
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import {
  MIGRATIONS_DIR,
  composeBuiltImages,
  imageDrift,
  pinDrift,
  pinErrors,
  pinVersions,
  renderNotes,
  resolvePlan,
  rewritePin,
} from './lib.ts';
import type { Commit, Image } from './lib.ts';

function output(values: Record<string, string | boolean>): void {
  const lines = Object.entries(values).map(([k, v]) => `${k}=${v}`);
  console.log(lines.join('\n'));
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, lines.join('\n') + '\n');
  }
}

function git(...args: string[]): string {
  return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function previousTag(ref: string): string {
  try {
    return git('describe', '--tags', '--abbrev=0', '--match', 'v[0-9]*', `${ref}^`);
  } catch {
    return ''; // no earlier v* tag (or a root commit): the first release covers everything
  }
}

// Run from the repo root, as the workflows do.
const ENV_EXAMPLE = '.env.example';
const COMPOSE = 'docker-compose.yml';

function checkPin(): boolean {
  const pins = pinVersions(readFileSync(ENV_EXAMPLE, 'utf8'), readFileSync(COMPOSE, 'utf8'));
  const errors = pinErrors(pins);
  for (const e of errors) console.log(`::error::${e}`);
  if (errors.length > 0) {
    console.log('Move every pin together: node .github/release/release.ts pin <version>');
    return false;
  }
  console.log(`Quickstart pin agrees: KELTA_VERSION=${pins.env} in ${ENV_EXAMPLE} and ${pins.compose.length} defaults in ${COMPOSE}`);
  return true;
}

const [command, ...args] = process.argv.slice(2);

switch (command) {
  case 'plan': {
    const env = process.env;
    output({
      ...resolvePlan({
        event: env.GITHUB_EVENT_NAME ?? '',
        refType: env.GITHUB_REF_TYPE ?? '',
        refName: env.GITHUB_REF_NAME ?? '',
        dryRunInput: env.DRY_RUN ?? '',
        sha: env.GITHUB_SHA ?? '',
      }),
    });
    break;
  }
  case 'check-images': {
    const release: Image[] = JSON.parse(process.env.IMAGES ?? '[]');
    const compose = composeBuiltImages(JSON.parse(readFileSync(args[0], 'utf8')));
    const base = JSON.parse(readFileSync(args[1], 'utf8'));
    const errors = [...imageDrift(release, compose), ...pinDrift(release, base, process.env.REGISTRY ?? 'ghcr.io/kelta-io')];
    if (errors.length > 0) {
      for (const e of errors) console.log(`::error::${e}`);
      console.log('Update IMAGES in .github/workflows/release.yml to match the quickstart compose stack.');
      process.exit(1);
    }
    console.log(`Released images match the quickstart stack: ${compose.map((i) => i.service).join(', ')}`);
    output({ images: JSON.stringify(release) });
    break;
  }
  case 'notes': {
    const [ref, out] = args;
    const prev = previousTag(ref);
    const range = prev ? `${prev}..${ref}` : ref;
    // Bodies span lines, so records end in \x1e (RS) and fields split on \x1f (US).
    const log = git('log', '--no-merges', '--format=%H%x1f%s%x1f%b%x1e', range);
    const commits: Commit[] = log
      .split('\x1e')
      .map((record) => record.trim())
      .filter(Boolean)
      .map((record) => {
        const [sha, subject, body] = record.split('\x1f');
        return { sha, subject, body };
      });
    // A first release has no previous schema to migrate from, so it lists none.
    const migrations = prev
      ? git('diff', '--name-only', '--diff-filter=A', prev, ref, '--', MIGRATIONS_DIR).split('\n').filter(Boolean)
      : [];
    const repo = process.env.GITHUB_REPOSITORY ?? 'kelta-io/kelta';
    writeFileSync(out, renderNotes({ commits, repo, previousTag: prev, ref, migrations }));
    console.log(`Wrote ${commits.length} commits and ${migrations.length} migrations since ${prev || 'the first commit'} to ${out}`);
    break;
  }
  case 'check-pin': {
    if (!checkPin()) process.exit(1);
    break;
  }
  case 'pin': {
    const [version] = args;
    if (!version) {
      console.error('usage: node .github/release/release.ts pin <version>   (e.g. 0.2.0, no leading v)');
      process.exit(2);
    }
    // Validate before touching either file, so a bad version never leaves them half-rewritten.
    const env = rewritePin(readFileSync(ENV_EXAMPLE, 'utf8'), version);
    const compose = rewritePin(readFileSync(COMPOSE, 'utf8'), version);
    writeFileSync(ENV_EXAMPLE, env);
    writeFileSync(COMPOSE, compose);
    if (!checkPin()) process.exit(1);
    break;
  }
  default:
    console.error(`unknown command "${command}" (plan | check-images | notes | check-pin | pin <version>)`);
    process.exit(2);
}
