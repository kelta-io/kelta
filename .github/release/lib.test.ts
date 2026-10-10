// node --test .github/release/lib.test.ts   (Node >= 24)
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { MAX_PER_SECTION, composeBuiltImages, imageDrift, parseTag, pinDrift, renderNotes, resolvePlan } from './lib.ts';

const ctx = { event: 'push', refType: 'tag', refName: 'v1.2.3', dryRunInput: '', sha: 'abcdef0123456789' };

describe('parseTag', () => {
  it('strips the leading v', () => {
    assert.deepEqual(parseTag('v1.2.3'), { version: '1.2.3', prerelease: false });
  });

  it('flags a prerelease suffix', () => {
    assert.deepEqual(parseTag('v1.0.0-rc.1'), { version: '1.0.0-rc.1', prerelease: true });
  });

  for (const bad of ['1.2.3', 'v1.2', 'v1.2.3.4', 'v1.2.3+build.5', 'v01.2.3', 'v1.2.3-']) {
    it(`rejects ${bad}`, () => {
      assert.throws(() => parseTag(bad), /not vMAJOR/);
    });
  }
});

describe('resolvePlan', () => {
  it('publishes a release tag as latest', () => {
    assert.deepEqual(resolvePlan(ctx), {
      publish: true,
      tag: 'v1.2.3',
      version: '1.2.3',
      prerelease: false,
      latest: true,
    });
  });

  it('never moves latest for a prerelease tag', () => {
    const plan = resolvePlan({ ...ctx, refName: 'v2.0.0-beta.1' });
    assert.equal(plan.publish, true);
    assert.equal(plan.prerelease, true);
    assert.equal(plan.latest, false);
  });

  it('is a dry run on pull_request', () => {
    const plan = resolvePlan({ ...ctx, event: 'pull_request', refType: 'branch', refName: '1633/merge' });
    assert.deepEqual(plan, {
      publish: false,
      tag: '',
      version: '0.0.0-dryrun.abcdef0',
      prerelease: true,
      latest: false,
    });
  });

  it('is a dry run on workflow_dispatch unless dry_run is false', () => {
    const dispatch = { ...ctx, event: 'workflow_dispatch' };
    assert.equal(resolvePlan({ ...dispatch, dryRunInput: 'true' }).publish, false);
    assert.equal(resolvePlan({ ...dispatch, dryRunInput: 'false' }).publish, true);
  });

  it('refuses to publish from a branch', () => {
    assert.throws(
      () => resolvePlan({ ...ctx, event: 'workflow_dispatch', dryRunInput: 'false', refType: 'branch', refName: 'main' }),
      /needs a v\*\.\*\.\* tag ref/,
    );
  });
});

const compose = {
  services: {
    postgres: {},
    'kelta-worker': {
      build: { context: '/w', dockerfile: 'kelta-worker/Dockerfile.jvm', args: { BASE_REGISTRY: 'docker.io/library', MAVEN_MIRROR: 'central' } },
    },
    'kelta-ui': { build: { context: '/w', dockerfile: 'kelta-ui/Dockerfile', args: { VITE_API_BASE_URL: 'http://localhost:8080' } } },
    'kelta-ai': { build: { dockerfile: 'kelta-ai/Dockerfile.jvm' }, profiles: ['ai'] },
    'kelta-bootstrap': { build: { context: '/w/docker/bootstrap' }, profiles: ['seed'] },
  },
};

const release = [
  { service: 'kelta-worker', dockerfile: 'kelta-worker/Dockerfile.jvm', args: { BASE_REGISTRY: 'docker.io/library', MAVEN_MIRROR: 'central' } },
  { service: 'kelta-ui', dockerfile: 'kelta-ui/Dockerfile', args: { VITE_API_BASE_URL: 'http://localhost:8080', BASE_REGISTRY: 'docker.io/library' } },
];

describe('composeBuiltImages', () => {
  it('keeps only built services outside a profile', () => {
    assert.deepEqual(
      composeBuiltImages(compose).map((i) => i.service),
      ['kelta-ui', 'kelta-worker'],
    );
  });
});

describe('imageDrift', () => {
  const built = composeBuiltImages(compose);

  it('accepts a matching list, extra args included', () => {
    assert.deepEqual(imageDrift(release, built), []);
  });

  it('reports a service compose builds but the release skips', () => {
    assert.deepEqual(imageDrift(release.slice(0, 1), built), ['kelta-ui: built by the quickstart stack but not released']);
  });

  it('reports a released service compose no longer builds', () => {
    const extra = [...release, { service: 'kelta-ai', dockerfile: 'kelta-ai/Dockerfile.jvm', args: {} }];
    assert.deepEqual(imageDrift(extra, built), ['kelta-ai: released but not built by the quickstart stack']);
  });

  it('reports a different Dockerfile or a changed build arg', () => {
    const native = [{ ...release[0], dockerfile: 'kelta-worker/Dockerfile', args: { BASE_REGISTRY: 'docker.io/library' } }, release[1]];
    assert.deepEqual(imageDrift(native, built), [
      'kelta-worker: released from kelta-worker/Dockerfile, compose builds kelta-worker/Dockerfile.jvm',
      'kelta-worker: build arg MAVEN_MIRROR=(unset), compose passes MAVEN_MIRROR=central',
    ]);
  });
});

describe('pinDrift', () => {
  const registry = 'ghcr.io/kelta-io';
  const base = {
    services: {
      postgres: { image: 'pgvector/pgvector:pg15' },
      'kelta-worker': { image: 'ghcr.io/kelta-io/kelta-worker:0.1.0' },
      'kelta-ui': { image: 'ghcr.io/kelta-io/kelta-ui:0.1.0' },
      'kelta-ai': { profiles: ['ai'] },
      'kelta-bootstrap': { build: { context: '/w/docker/bootstrap' }, profiles: ['seed'] },
    },
  };

  it('accepts a base file that pulls every released image and builds nothing by default', () => {
    assert.deepEqual(pinDrift(release, base, registry), []);
  });

  it('reports a released service the base file builds instead of pulling', () => {
    const built = { services: { ...base.services, 'kelta-ui': { build: { dockerfile: 'kelta-ui/Dockerfile' } } } };
    assert.deepEqual(pinDrift(release, built, registry), [
      'kelta-ui: base compose runs (no image), not the released ghcr.io/kelta-io/kelta-ui:<version>',
      'kelta-ui: base compose builds from source; move its build: to docker-compose.build.yml',
    ]);
  });

  it('reports a base image from another registry', () => {
    const local = { services: { ...base.services, 'kelta-worker': { image: 'kelta-worker:local' } } };
    assert.deepEqual(pinDrift(release, local, registry), [
      'kelta-worker: base compose runs kelta-worker:local, not the released ghcr.io/kelta-io/kelta-worker:<version>',
    ]);
  });

  it('reports a pulled registry image the release does not publish', () => {
    const extra = { services: { ...base.services, 'kelta-mcp': { image: 'ghcr.io/kelta-io/kelta-mcp:0.1.0' } } };
    assert.deepEqual(pinDrift(release, extra, registry), [
      'kelta-mcp: base compose pulls ghcr.io/kelta-io/kelta-mcp:0.1.0, which the release does not publish',
    ]);
  });
});

describe('renderNotes', () => {
  const commits = [
    { sha: '1111111aaaa', subject: 'feat(ui): export flows from Packages (#1633)' },
    { sha: '2222222bbbb', subject: 'fix(auth): keep UI sessions alive (#1630)' },
    { sha: '3333333cccc', subject: 'refactor(sdk)!: remove the legacy roles APIs (#1632)' },
    { sha: '4444444dddd', subject: 'KLT-369: Render the docs quickstart page (#1629)' },
    { sha: '5555555eeee', subject: 'Feat: capitalised type still counts' },
  ];

  it('groups by conventional-commit type with a compare link', () => {
    const notes = renderNotes({ commits, repo: 'kelta-io/kelta', previousTag: 'v0.1.0', ref: 'v0.2.0' });
    assert.equal(
      notes,
      [
        '5 commits since v0.1.0.',
        '',
        '## Features',
        '',
        '- feat(ui): export flows from Packages (#1633) (1111111)',
        '- Feat: capitalised type still counts (5555555)',
        '',
        '## Bug fixes',
        '',
        '- fix(auth): keep UI sessions alive (#1630) (2222222)',
        '',
        '## Other changes',
        '',
        '- **BREAKING** refactor(sdk)!: remove the legacy roles APIs (#1632) (3333333)',
        '- KLT-369: Render the docs quickstart page (#1629) (4444444)',
        '',
        '**Full changelog**: https://github.com/kelta-io/kelta/compare/v0.1.0...v0.2.0',
        '',
      ].join('\n'),
    );
  });

  it('falls back to the full history and omits empty sections on a first release', () => {
    const notes = renderNotes({ commits: commits.slice(1, 2), repo: 'kelta-io/kelta', previousTag: '', ref: 'v0.1.0' });
    assert.match(notes, /^1 commit since the start of the history\./);
    assert.doesNotMatch(notes, /## Features|## Other changes/);
    assert.match(notes, /\*\*Full history\*\*: https:\/\/github\.com\/kelta-io\/kelta\/commits\/v0\.1\.0/);
  });

  it('caps each section so a full-history release fits GitHub’s body limit', () => {
    const many = Array.from({ length: MAX_PER_SECTION + 7 }, (_, i) => ({ sha: `${i}`.padStart(7, '0'), subject: `chore: change ${i}` }));
    const notes = renderNotes({ commits: many, repo: 'kelta-io/kelta', previousTag: '', ref: 'v0.1.0' });
    assert.equal(notes.split('\n').filter((l) => l.startsWith('- chore:')).length, MAX_PER_SECTION);
    assert.match(notes, /- …and 7 more/);
  });
});
