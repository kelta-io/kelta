// node --test ci/verify-kelta-billing-job.test.ts   (Node >= 24)
//
// Static guard on the post-deploy kelta-billing check in build-and-publish-containers.yml. The
// external module is installed on no tenant by deploying it, and the platform does not depend on
// it, so its check must fail the run visibly but never trigger rollback-on-smoke-failure, which
// reverts the whole homelab-argo bump. It lived inside smoke-test once; a module failure there
// would have rolled the platform back. This job runs only after a deploy on main, so no PR check
// exercises it.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';

const REPO = join(import.meta.dirname, '..');
const WORKFLOW = readFileSync(
  join(REPO, '.github/workflows/build-and-publish-containers.yml'),
  'utf8',
);

// A job, from its key up to the next two-space-indented job key (or EOF).
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

// The job's `needs:` value as a list of job ids.
function needsOf(block: string): string[] {
  const match = block.match(/^ {4}needs: (.+)$/m);
  assert.ok(match, 'the job has a one-line `needs:`');
  return match[1]
    .replace(/[[\]]/g, '')
    .split(',')
    .map((need) => need.trim())
    .filter(Boolean);
}

const VERIFY = jobBlock(WORKFLOW, 'verify-kelta-billing');
const SMOKE = jobBlock(WORKFLOW, 'smoke-test');
const ROLLBACK = jobBlock(WORKFLOW, 'rollback-on-smoke-failure');

describe('build-and-publish-containers.yml verify-kelta-billing job', () => {
  it('runs after a successful deploy, only when this run built the module', () => {
    assert.deepEqual(needsOf(VERIFY), ['changes', 'deploy']);
    assert.match(
      VERIFY,
      /^ {4}if: needs\.deploy\.result == 'success' && needs\.changes\.outputs\.rust_modules == 'true'$/m,
    );
  });

  it('reads the manifest from the worker at the rolled tag', () => {
    assert.ok(VERIFY.includes('EXPECT="main-$(echo ${{ github.sha }} | cut -c1-7)"'));
    assert.match(VERIFY, /exec deploy\/"\$\{W_DEPLOY\}" -- curl -sf --max-time 5 "\$\{URL\}"/);
    assert.match(VERIFY, /grep -o '"id":"kelta-billing"'/);
  });

  it('does not read step outputs from another job', () => {
    assert.doesNotMatch(VERIFY, /steps\.k8s\.outputs/);
  });

  it('is no longer a smoke-test step', () => {
    // Comments dropped: the block runs on to the next job key, past this job's header comment.
    const code = SMOKE.split('\n')
      .filter((line) => !/^\s*#/.test(line))
      .join('\n');
    assert.doesNotMatch(code, /kelta-billing/);
  });

  it('can never trigger the platform rollback', () => {
    assert.deepEqual(needsOf(ROLLBACK), ['smoke-test']);
    assert.doesNotMatch(ROLLBACK, /verify-kelta-billing/);
  });
});
