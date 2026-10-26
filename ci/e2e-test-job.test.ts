// node --test ci/e2e-test-job.test.ts   (Node >= 24)
//
// Static guard on the post-deploy `e2e-test` job in build-and-publish-containers.yml. That job
// only runs after a deploy on main, so no PR check exercises it: PLT-808's
// PLAYWRIGHT_HOST_PLATFORM_OVERRIDE + `playwright install --with-deps` on the focal k8s-runner
// broke every main deploy (PLT-818/PLT-825) without a single red PR. Playwright has to run in
// the noble image whose tag comes from e2e-tests/package-lock.json, as ci.yml's `e2e` job does.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';

const REPO = join(import.meta.dirname, '..');
const WORKFLOW = readFileSync(join(REPO, '.github/workflows/build-and-publish-containers.yml'), 'utf8');
const LOCKFILE = 'e2e-tests/package-lock.json';

// The `e2e-test:` job, from its key up to the next two-space-indented job key (or EOF).
function jobBlock(workflow: string, job: string): string {
  const lines = workflow.split('\n');
  const start = lines.indexOf(`  ${job}:`);
  assert.notEqual(start, -1, `no \`${job}:\` job in build-and-publish-containers.yml`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (/^ {2}[A-Za-z0-9_-]+:\s*$/.test(lines[i]) || /^\S/.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end).join('\n');
}

const JOB = jobBlock(WORKFLOW, 'e2e-test');

describe('build-and-publish-containers.yml e2e-test job', () => {
  it('slices the whole job, through its last step', () => {
    assert.match(JOB, /^ {2}e2e-test:\n {4}name: E2E Tests\n/);
    assert.match(JOB, /name: Upload failing pod logs/);
  });

  it('does not pin a Playwright host platform', () => {
    assert.doesNotMatch(JOB, /PLAYWRIGHT_HOST_PLATFORM_OVERRIDE/);
  });

  it('does not install browsers or OS dependencies on the runner host', () => {
    assert.doesNotMatch(JOB, /--with-deps/);
    assert.doesNotMatch(JOB, /playwright install/);
  });

  it(`reads PLAYWRIGHT_VERSION from ${LOCKFILE} with jq and fails when it is empty or null`, () => {
    assert.ok(
      JOB.includes(
        `PLAYWRIGHT_VERSION=$(jq -r '.packages["node_modules/@playwright/test"].version' ${LOCKFILE})`,
      ),
      `PLAYWRIGHT_VERSION is not read with jq from ${LOCKFILE}`,
    );
    assert.ok(
      JOB.includes('if [ -z "$PLAYWRIGHT_VERSION" ] || [ "$PLAYWRIGHT_VERSION" = "null" ]; then'),
      'no empty/null guard on PLAYWRIGHT_VERSION',
    );
    assert.match(JOB, /"\$PLAYWRIGHT_VERSION" = "null" \]; then\n\s+echo "::error::[^\n]*"\n\s+exit 1\n/);
  });

  it('builds the runner image FROM the noble Playwright image at that version', () => {
    assert.ok(JOB.includes('--build-arg PLAYWRIGHT_VERSION="$PLAYWRIGHT_VERSION"'), 'version not passed as a build arg');
    assert.match(JOB, /\n\s+ARG PLAYWRIGHT_VERSION\n\s+FROM mcr\.microsoft\.com\/playwright:v\$\{PLAYWRIGHT_VERSION\}-noble\n/);
    assert.doesNotMatch(JOB, /mcr\.microsoft\.com\/playwright:v\d/, 'hard-coded Playwright image version');
  });

  it(`resolves to a real version in ${LOCKFILE}`, () => {
    const lock = JSON.parse(readFileSync(join(REPO, LOCKFILE), 'utf8'));
    const version = lock.packages?.['node_modules/@playwright/test']?.version;
    assert.match(String(version), /^\d+\.\d+\.\d+/);
  });

  it('bakes e2e-tests and kelta-web into the image instead of bind-mounting the workspace', () => {
    assert.match(JOB, /\n\s+WORKDIR \/work\n/);
    assert.match(JOB, /\n\s+RUN npm ci\n/);
    assert.match(JOB, /\n\s+COPY e2e-tests\/ \.\n/);
    assert.match(JOB, /\n\s+COPY kelta-web \/kelta-web\n/);
    assert.doesNotMatch(JOB, /docker run[^\n]*(?:\\\n[^\n]*)*\s(?:-v|--volume|--mount)\s/);
  });

  it('runs npx playwright test in the container with the env file, then copies reports out', () => {
    assert.match(JOB, /docker run --name "\$CONTAINER" \\\n\s+--env-file "\$RUNNER_TEMP\/e2e-postdeploy\.env" \\\n[^\n]*\\\n\s+npx playwright test\n/);
    const copy = JOB.slice(JOB.indexOf('- name: Copy Playwright reports out of container'));
    assert.match(copy, /^- name: [^\n]+\n\s+if: always\(\)/);
    assert.ok(copy.includes('docker cp "$CONTAINER":/work/playwright-report/. e2e-tests/playwright-report/'));
    assert.ok(copy.includes('docker cp "$CONTAINER":/work/test-results/. e2e-tests/test-results/'));
    assert.ok(JOB.indexOf('Copy Playwright reports out of container') < JOB.indexOf('- name: Upload test report'));
  });

  it('passes every E2E_* variable of the env step through the env file', () => {
    const step = JOB.slice(JOB.indexOf('- name: Write E2E runner environment'), JOB.indexOf('- name: Run E2E tests'));
    const declared = [...step.matchAll(/^\s+(E2E_[A-Z_]+):/gm)].map((m) => m[1]);
    assert.ok(declared.includes('E2E_API_TOKEN') && declared.includes('E2E_BASE_URL'));
    const loop = step.match(/for name in ([\s\S]*?); do/);
    assert.ok(loop, 'no env-file loop');
    const written = loop[1].split(/[\s\\]+/).filter(Boolean);
    assert.deepEqual([...written].sort(), [...declared].sort());
    assert.match(step, /echo "CI=true"/);
    assert.match(step, /> "\$RUNNER_TEMP\/e2e-postdeploy\.env"/);
  });
});
