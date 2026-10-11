// node --test .github/release/lib.test.ts   (Node >= 24)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import {
  MAX_PER_SECTION,
  composeBuiltImages,
  imageDrift,
  parseTag,
  pinDrift,
  pinErrors,
  pinVersions,
  renderNotes,
  resolvePlan,
  rewritePin,
} from './lib.ts';

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
      'kelta-ai': { image: 'kelta-ai:local', profiles: ['ai'] },
      'kelta-bootstrap': { image: 'kelta-bootstrap:local', profiles: ['seed'] },
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
        '## Breaking changes',
        '',
        '- refactor(sdk)!: remove the legacy roles APIs (#1632) (3333333)',
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

  const at = (notes: string, title: string): string =>
    notes.split('## ').find((s) => s.startsWith(title)) ?? '';

  it('types a squash-merge subject from its body and keeps the subject text', () => {
    const notes = renderNotes({
      commits: [{ sha: '6666666ffff', subject: 'KLT-1: Do a thing (#9)', body: '* fix(x): do a thing\n\nTask: KLT-1' }],
      repo: 'kelta-io/kelta',
      previousTag: 'v0.1.0',
      ref: 'v0.1.1',
    });
    assert.match(at(notes, 'Bug fixes'), /^- KLT-1: Do a thing \(#9\) \(6666666\)$/m);
    assert.doesNotMatch(notes, /## Other changes|## Breaking changes/);
  });

  it('takes the first conventional body line, not a later one', () => {
    const notes = renderNotes({
      commits: [
        {
          sha: '7777777aaaa',
          subject: 'KLT-2: Add a widget (#10)',
          body: 'Adds the widget.\n\n* feat(ui): add a widget\n* chore: tidy imports\n\nTask: KLT-2',
        },
      ],
      repo: 'kelta-io/kelta',
      previousTag: 'v0.1.0',
      ref: 'v0.1.1',
    });
    assert.match(at(notes, 'Features'), /^- KLT-2: Add a widget \(#10\) \(7777777\)$/m);
    assert.doesNotMatch(notes, /## Other changes/);
  });

  it('leaves a subject with no conventional body line, or only a trailer, under Other changes', () => {
    const notes = renderNotes({
      commits: [
        { sha: '8888888aaaa', subject: 'KLT-3: Rename a page (#11)', body: 'Just prose here.\nNo prefix.' },
        { sha: '9999999aaaa', subject: 'KLT-4: Move a file (#12)', body: 'Task: KLT-1' },
      ],
      repo: 'kelta-io/kelta',
      previousTag: 'v0.1.0',
      ref: 'v0.1.1',
    });
    const other = at(notes, 'Other changes');
    assert.match(other, /^- KLT-3: Rename a page \(#11\) \(8888888\)$/m);
    assert.match(other, /^- KLT-4: Move a file \(#12\) \(9999999\)$/m);
    assert.doesNotMatch(notes, /## Features|## Bug fixes/);
  });

  it('lists BREAKING CHANGE footers and `!` types under Breaking changes', () => {
    const notes = renderNotes({
      commits: [
        { sha: 'aaaaaaa1111', subject: 'KLT-5: Drop an env var (#13)', body: '* chore(ops): drop X\n\nBREAKING CHANGE: drops the X env var' },
        { sha: 'bbbbbbb2222', subject: 'feat!: new auth flow (#14)' },
        { sha: 'ccccccc3333', subject: 'KLT-6: Rework the API (#15)', body: '* refactor(api)!: rework the API' },
        { sha: 'ddddddd4444', subject: 'fix: not breaking (#16)' },
      ],
      repo: 'kelta-io/kelta',
      previousTag: 'v0.1.0',
      ref: 'v0.1.1',
    });
    assert.match(notes, /^4 commits since v0\.1\.0\.\n\n## Breaking changes\n/);
    const breaking = at(notes, 'Breaking changes');
    assert.match(breaking, /^- KLT-5: Drop an env var \(#13\) \(aaaaaaa\): drops the X env var$/m);
    assert.match(breaking, /^- feat!: new auth flow \(#14\) \(bbbbbbb\)$/m);
    assert.match(breaking, /^- KLT-6: Rework the API \(#15\) \(ccccccc\)$/m);
    assert.doesNotMatch(breaking, /not breaking/);
    assert.match(at(notes, 'Features'), /^- \*\*BREAKING\*\* feat!: new auth flow/m);
    assert.match(at(notes, 'Other changes'), /^- \*\*BREAKING\*\* KLT-5: Drop an env var/m);
  });

  it('lists the migrations a release adds with a pointer to the upgrade guide', () => {
    const notes = renderNotes({
      commits: commits.slice(1, 2),
      repo: 'kelta-io/kelta',
      previousTag: 'v0.1.0',
      ref: 'v0.1.1',
      migrations: [
        'kelta-worker/src/main/resources/db/migration/V2__add_widgets.sql',
        'kelta-worker/src/main/resources/db/migration/V3__index_widgets.sql',
      ],
    });
    const section = at(notes, 'Database migrations');
    assert.match(section, /^- `V2__add_widgets\.sql`$/m);
    assert.match(section, /^- `V3__index_widgets\.sql`$/m);
    assert.match(section, /Back up before upgrading; Flyway does not run down-migrations/);
    assert.match(section, /UPGRADING\.md/);
  });

  it('omits the migrations section when the release adds none', () => {
    const notes = renderNotes({ commits: commits.slice(1, 2), repo: 'kelta-io/kelta', previousTag: 'v0.1.0', ref: 'v0.1.1', migrations: [] });
    assert.doesNotMatch(notes, /## Database migrations/);
  });
});

const envExample = (v: string): string => `# comment\nPOSTGRES_PASSWORD=x\nKELTA_VERSION=${v}\nOTHER=y\n`;
const composeFile = (...versions: string[]): string =>
  'services:\n' +
  versions.map((v, i) => `  svc${i}:\n    image: ghcr.io/kelta-io/svc${i}:\${KELTA_VERSION:-${v}}\n`).join('');

const errorsFor = (env: string, compose: string): string[] => pinErrors(pinVersions(env, compose));

describe('pinVersions / pinErrors', () => {
  it('reads the .env.example value and every compose default', () => {
    assert.deepEqual(pinVersions(envExample('0.1.0'), composeFile('0.1.0', '0.1.0', '0.1.0', '0.1.0')), {
      env: '0.1.0',
      compose: ['0.1.0', '0.1.0', '0.1.0', '0.1.0'],
    });
  });

  it('accepts four agreeing defaults', () => {
    assert.deepEqual(errorsFor(envExample('0.1.0'), composeFile('0.1.0', '0.1.0', '0.1.0', '0.1.0')), []);
  });

  it('flags .env.example drifting from compose', () => {
    const errors = errorsFor(envExample('9.9.9'), composeFile('0.1.0', '0.1.0', '0.1.0', '0.1.0'));
    assert.deepEqual(errors, ['.env.example pins KELTA_VERSION=9.9.9, docker-compose.yml defaults to 0.1.0']);
  });

  it('flags compose defaults that disagree with each other', () => {
    const errors = errorsFor(envExample('0.2.0'), composeFile('0.2.0', '0.2.0', '0.1.0', '0.2.0'));
    assert.ok(errors.some((e) => /defaults disagree: 0\.2\.0, 0\.1\.0/.test(e)), errors.join('\n'));
  });

  it('flags fewer than four compose defaults', () => {
    const errors = errorsFor(envExample('0.1.0'), composeFile('0.1.0', '0.1.0', '0.1.0'));
    assert.deepEqual(errors, [
      'docker-compose.yml has 3 ${KELTA_VERSION:-X} defaults, expected at least 4 (one per released image)',
    ]);
  });

  it('flags a missing KELTA_VERSION= line', () => {
    const pins = pinVersions('POSTGRES_PASSWORD=x\n# KELTA_VERSION=0.1.0\n', composeFile('0.1.0', '0.1.0', '0.1.0', '0.1.0'));
    assert.equal(pins.env, null);
    assert.deepEqual(pinErrors(pins), ['.env.example has no KELTA_VERSION= line']);
  });

  it('agrees on the real .env.example and docker-compose.yml', () => {
    const root = new URL('../../', import.meta.url);
    const pins = pinVersions(
      readFileSync(new URL('.env.example', root), 'utf8'),
      readFileSync(new URL('docker-compose.yml', root), 'utf8'),
    );
    assert.deepEqual(pinErrors(pins), []);
    assert.ok(pins.compose.length >= 4);
    assert.doesNotThrow(() => parseTag(`v${pins.env}`));
  });
});

describe('rewritePin', () => {
  it('moves both forms and leaves everything else alone', () => {
    const env = rewritePin(envExample('0.1.0'), '0.2.0');
    const compose = rewritePin(composeFile('0.1.0', '0.1.0', '0.1.0', '0.1.0'), '0.2.0');
    assert.equal(env, envExample('0.2.0'));
    assert.equal(compose, composeFile('0.2.0', '0.2.0', '0.2.0', '0.2.0'));
    assert.deepEqual(errorsFor(env, compose), []);
  });

  it('accepts a prerelease version', () => {
    assert.equal(rewritePin(envExample('0.1.0'), '1.0.0-rc.1'), envExample('1.0.0-rc.1'));
  });

  for (const bad of ['v0.2.0', '0.2', '0.2.0.1', '0.2.0+build', '01.2.0', '']) {
    it(`rejects the invalid version "${bad}"`, () => {
      assert.throws(() => rewritePin(envExample('0.1.0'), bad), /not vMAJOR/);
    });
  }
});
