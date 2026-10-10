// node --test ci/update-snapshots.test.ts   (Node >= 24; needs bash and git)
//
// Drives ci/update-snapshots.sh against a local bare repo standing in for github.com and a fake
// docker that records its calls and, on `docker cp <c>:/work/<spec>-snapshots/.`, drops the PNGs
// a real `playwright test --update-snapshots` run would have written.
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, beforeEach, describe, it } from 'node:test';

const SCRIPT = join(import.meta.dirname, 'update-snapshots.sh');
const SPEC = 'tests/end-user/object-detail-visual.spec.ts';
const SNAPSHOTS = `e2e-tests/${SPEC}-snapshots`;
const BRANCH = 'autopilot/plt-476';

const FAKE_DOCKER = `#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_LOG"
case "$1" in
  run) exit "\${FAKE_RUN_STATUS:-0}" ;;
  cp)
    src="\${2#*:}"; dest="$3"
    case "$src" in
      *-snapshots/.) [ -d "$FAKE_PNGS" ] && cp -R "$FAKE_PNGS"/. "$dest" ;;
    esac
    exit 0 ;;
esac
exit 0
`;

const roots: string[] = [];
let root: string;
let origin: string;
let seed: string;
let dockerLog: string;
let pngs: string;
let summary: string;

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 't',
      GIT_AUTHOR_EMAIL: 't@t',
      GIT_COMMITTER_NAME: 't',
      GIT_COMMITTER_EMAIL: 't@t',
    },
  }).trim();
}

function write(path: string, content: string | Buffer): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

// Puts `files` on BRANCH in the stand-in origin and returns the branch head.
function pushBranch(files: Record<string, string>): string {
  for (const [path, content] of Object.entries(files)) write(join(seed, path), content);
  git(seed, 'add', '-A');
  git(seed, 'commit', '--quiet', '-m', 'pr change');
  git(seed, 'push', '--quiet', 'origin', `HEAD:refs/heads/${BRANCH}`);
  return git(seed, 'rev-parse', 'HEAD');
}

function run(env: Record<string, string>): Promise<{ code: number; out: string }> {
  return new Promise((resolve) => {
    execFile(
      'bash',
      [SCRIPT],
      {
        cwd: root,
        env: {
          PATH: process.env.PATH ?? '',
          HOME: root,
          SNAPSHOTS_REMOTE: origin,
          COMPOSE_PROJECT_NAME: 'ci-1-1',
          E2E_ENV_FILE: join(root, 'runner.env'),
          GITHUB_STEP_SUMMARY: summary,
          DOCKER: join(root, 'docker'),
          FAKE_DOCKER_LOG: dockerLog,
          FAKE_PNGS: pngs,
          REPORT_DIR: join(root, 'reports'),
          ...env,
        },
      },
      (error, stdout, stderr) => {
        resolve({ code: error ? Number(error.code) : 0, out: stdout + stderr });
      }
    );
  });
}

function remoteFiles(): string[] {
  return git(origin, 'ls-tree', '-r', '--name-only', BRANCH).split('\n');
}

function lastCommitFiles(): string {
  return git(origin, 'show', '--name-status', '--format=', BRANCH);
}

function dockerCalls(): string {
  try {
    return readFileSync(dockerLog, 'utf8');
  } catch {
    return '';
  }
}

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'update-snapshots-'));
  roots.push(root);
  origin = join(root, 'origin.git');
  seed = join(root, 'seed');
  dockerLog = join(root, 'docker.log');
  pngs = join(root, 'pngs');
  summary = join(root, 'summary.md');
  write(join(root, 'docker'), FAKE_DOCKER);
  chmodSync(join(root, 'docker'), 0o755);
  write(join(root, 'runner.env'), 'CI=true\n');

  git(root, 'init', '--quiet', '--bare', origin);
  git(root, 'init', '--quiet', '-b', 'main', seed);
  git(seed, 'remote', 'add', 'origin', origin);
  write(join(seed, 'e2e-tests', SPEC), 'test("x", () => {});\n');
  write(join(seed, 'e2e-tests/tests/end-user/other.spec.ts'), 'test("y", () => {});\n');
  write(join(seed, SNAPSHOTS, 'detail-chromium-linux.png'), 'old-detail');
  write(join(seed, SNAPSHOTS, 'list-chromium-linux.png'), 'old-list');
  git(seed, 'add', '-A');
  git(seed, 'commit', '--quiet', '-m', 'base');
  git(seed, 'push', '--quiet', 'origin', 'HEAD:refs/heads/main');
});

after(() => {
  for (const dir of roots) rmSync(dir, { recursive: true, force: true });
});

describe('update-snapshots.sh', () => {
  it('commits only the changed PNGs plus the marker deletion, pushes the branch and fails the run', async () => {
    const headSha = pushBranch({
      'e2e-tests/.update-snapshots': `# regenerate after the layout fix\n\n  ${SPEC}  # inline note\n`,
    });
    write(join(pngs, 'detail-chromium-linux.png'), 'new-detail');
    write(join(pngs, 'list-chromium-linux.png'), 'old-list');
    write(join(pngs, 'notes.txt'), 'not a baseline');

    const { code, out } = await run({ SNAPSHOTS_REF: BRANCH, EXPECTED_SHA: headSha });

    assert.equal(code, 1, out);
    const pushed = git(origin, 'rev-parse', BRANCH);
    assert.notEqual(pushed, headSha);
    assert.equal(git(origin, 'rev-parse', `${BRANCH}^`), headSha, 'one commit on top of the PR head');
    assert.equal(
      lastCommitFiles(),
      `D\te2e-tests/.update-snapshots\nM\t${SNAPSHOTS}/detail-chromium-linux.png`
    );
    assert.equal(git(origin, 'show', `${BRANCH}:${SNAPSHOTS}/detail-chromium-linux.png`), 'new-detail');
    assert.equal(
      git(origin, 'log', '-1', '--format=%an <%ae>', BRANCH),
      'github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>'
    );
    const expected = `baselines regenerated for ${SPEC} in ${pushed}; CI re-runs on that commit`;
    assert.equal(readFileSync(summary, 'utf8').trim(), expected);
    assert.match(out, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));

    const calls = dockerCalls();
    assert.match(
      calls,
      new RegExp(
        `^run --name ci-1-1_kelta-e2e-snapshots --network ci-1-1_kelta-network --env-file ${join(root, 'runner.env')} kelta-e2e-runner:local npx playwright test --update-snapshots ${SPEC}$`,
        'm'
      )
    );
    assert.match(calls, new RegExp(`^cp ci-1-1_kelta-e2e-snapshots:/work/${SPEC}-snapshots/\\. `, 'm'));
  });

  it('still removes the marker, and says so, when no baseline changed', async () => {
    pushBranch({ 'e2e-tests/.update-snapshots': `${SPEC}\n` });
    write(join(pngs, 'detail-chromium-linux.png'), 'old-detail');

    const { code, out } = await run({ SNAPSHOTS_REF: BRANCH });

    assert.equal(code, 1, out);
    assert.equal(lastCommitFiles(), 'D\te2e-tests/.update-snapshots');
    const pushed = git(origin, 'rev-parse', BRANCH);
    assert.equal(
      readFileSync(summary, 'utf8').trim(),
      `no baseline changed for ${SPEC}; removed the marker in ${pushed}; CI re-runs on that commit`
    );
  });

  it('takes the specs from SNAPSHOTS_SPECS on the dispatch path, with no marker on the branch', async () => {
    const headSha = pushBranch({ 'e2e-tests/README.md': 'x\n' });
    write(join(pngs, 'new-chromium-linux.png'), 'new');

    const { code, out } = await run({
      SNAPSHOTS_REF: BRANCH,
      SNAPSHOTS_SPECS: `${SPEC}  tests/end-user/other.spec.ts`,
    });

    assert.equal(code, 1, out);
    assert.equal(git(origin, 'rev-parse', `${BRANCH}^`), headSha);
    assert.equal(
      lastCommitFiles(),
      `A\t${SNAPSHOTS}/new-chromium-linux.png\nA\te2e-tests/tests/end-user/other.spec.ts-snapshots/new-chromium-linux.png`
    );
    assert.match(dockerCalls(), new RegExp(`--update-snapshots ${SPEC} tests/end-user/other.spec.ts$`, 'm'));
  });

  it('refuses main', async () => {
    const { code, out } = await run({ SNAPSHOTS_REF: 'main', SNAPSHOTS_SPECS: SPEC });

    assert.equal(code, 1);
    assert.match(out, /refusing to push baselines to main/);
    assert.equal(dockerCalls(), '');
  });

  for (const [spec, reason] of [
    ['/work/tests/end-user/object-detail-visual.spec.ts', /is absolute/],
    ['tests/../helpers/x.spec.ts', /contains '\.\.'/],
    ['helpers/x.spec.ts', /is not under e2e-tests\/tests\//],
    ['tests/end-user/missing.spec.ts', /does not exist/],
    ['--reporter=line', /characters outside|not under/],
    ['tests/end-user/escape.spec.ts', /does not resolve under e2e-tests\/tests\//],
  ] as const) {
    it(`rejects '${spec}' by name before running playwright or pushing`, async () => {
      write(join(seed, 'e2e-tests/helpers/x.spec.ts'), 'x\n');
      mkdirSync(join(seed, 'e2e-tests/tests/end-user'), { recursive: true });
      symlinkSync('../../helpers/x.spec.ts', join(seed, 'e2e-tests/tests/end-user/escape.spec.ts'));
      const headSha = pushBranch({ 'e2e-tests/.update-snapshots': `${SPEC}\n${spec}\n` });

      const { code, out } = await run({ SNAPSHOTS_REF: BRANCH });

      assert.equal(code, 1);
      assert.match(out, reason);
      assert.ok(out.includes(`'${spec}'`), out);
      assert.equal(dockerCalls(), '');
      assert.equal(git(origin, 'rev-parse', BRANCH), headSha);
    });
  }

  it('aborts when the branch moved past the commit this run was started for', async () => {
    const first = pushBranch({ 'e2e-tests/.update-snapshots': `${SPEC}\n` });
    const second = pushBranch({ 'e2e-tests/README.md': 'newer\n' });

    const { code, out } = await run({ SNAPSHOTS_REF: BRANCH, EXPECTED_SHA: first });

    assert.equal(code, 1);
    assert.match(out, /moved to/);
    assert.equal(git(origin, 'rev-parse', BRANCH), second);
    assert.equal(dockerCalls(), '');
  });

  it('pushes nothing when playwright fails while updating', async () => {
    const headSha = pushBranch({ 'e2e-tests/.update-snapshots': `${SPEC}\n` });
    write(join(pngs, 'detail-chromium-linux.png'), 'new-detail');

    const { code, out } = await run({ SNAPSHOTS_REF: BRANCH, FAKE_RUN_STATUS: '3' });

    assert.equal(code, 1);
    assert.match(out, /playwright exited 3/);
    assert.equal(git(origin, 'rev-parse', BRANCH), headSha);
    assert.equal(remoteFiles().includes('e2e-tests/.update-snapshots'), true);
  });

  it('fails without a marker or specs', async () => {
    pushBranch({ 'e2e-tests/README.md': 'x\n' });

    const { code, out } = await run({ SNAPSHOTS_REF: BRANCH });

    assert.equal(code, 1);
    assert.match(out, /\.update-snapshots does not exist/);
  });

  it('refuses to run without the push token outside tests', async () => {
    const { code, out } = await run({ SNAPSHOTS_REF: BRANCH, SNAPSHOTS_SPECS: SPEC, SNAPSHOTS_REMOTE: '' });

    assert.equal(code, 1);
    assert.match(out, /PUSH_TOKEN is empty/);
  });
});
