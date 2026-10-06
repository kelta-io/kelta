// CLI for .github/workflows/release.yml. Usage (Node >= 24):
//   node .github/release/release.ts plan                    → GITHUB_OUTPUT: publish, tag, version, prerelease, latest
//   node .github/release/release.ts check-images <compose.json>  → GITHUB_OUTPUT: images; exit 1 on drift
//   node .github/release/release.ts notes <ref> <out.md>    → release notes since the previous v* tag
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { composeBuiltImages, imageDrift, renderNotes, resolvePlan } from './lib.ts';
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
    const errors = imageDrift(release, compose);
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
    const log = git('log', '--no-merges', '--format=%H%x1f%s', prev ? `${prev}..${ref}` : ref);
    const commits: Commit[] = log
      ? log.split('\n').map((line) => {
          const [sha, subject] = line.split('\x1f');
          return { sha, subject };
        })
      : [];
    const repo = process.env.GITHUB_REPOSITORY ?? 'kelta-io/kelta';
    writeFileSync(out, renderNotes({ commits, repo, previousTag: prev, ref }));
    console.log(`Wrote ${commits.length} commits since ${prev || 'the first commit'} to ${out}`);
    break;
  }
  default:
    console.error(`unknown command "${command}" (plan | check-images | notes)`);
    process.exit(2);
}
