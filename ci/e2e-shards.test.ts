// node --test ci/e2e-shards.test.ts   (Node >= 24)
//
// Static guard on the sharded `e2e` job in ci.yml (PLT-868). The shards share the node's
// Docker daemon and one workflow run, so everything a shard names must carry its number:
// a shared concurrency group cancels one shard from the other, a shared compose project
// tears down the other shard's stack, a shared artifact name fails the second upload.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';

const WORKFLOW = readFileSync(join(import.meta.dirname, '..', '.github/workflows/ci.yml'), 'utf8');

// The `<job>:` block, from its key up to the next two-space-indented job key (or EOF).
function jobBlock(workflow: string, job: string): string {
  const lines = workflow.split('\n');
  const start = lines.indexOf(`  ${job}:`);
  assert.notEqual(start, -1, `no \`${job}:\` job in ci.yml`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (/^ {2}[A-Za-z0-9_-]+:\s*$/.test(lines[i]) || /^\S/.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end).join('\n');
}

// One step, from its `- name:` line up to the next step.
function stepBlock(job: string, name: string): string {
  const start = job.indexOf(`      - name: ${name}`);
  assert.notEqual(start, -1, `no step "${name}" in the e2e job`);
  const next = job.indexOf('\n      - name: ', start + 1);
  return job.slice(start, next === -1 ? undefined : next);
}

const JOB = jobBlock(WORKFLOW, 'e2e');
const SHARD = /\$\{\{ matrix\.shard \}\}/;

describe('ci.yml e2e job shards', () => {
  const matrix = JOB.match(/\n {6}matrix:\n {8}shard: \[([0-9, ]+)\]\n/);

  it('runs a shard matrix without fail-fast', () => {
    assert.ok(matrix, 'no `matrix: shard: [...]` in the e2e job');
    assert.match(JOB, /\n {4}strategy:\n {6}fail-fast: false\n/);
  });

  it('passes every shard to Playwright as <n>/<matrix size>', () => {
    const shards = matrix![1].split(',').map((s) => Number(s.trim()));
    assert.deepEqual(shards, shards.map((_, i) => i + 1), 'shards must be 1..N');
    assert.match(JOB, new RegExp(`\\n {6}E2E_SHARD: \\$\\{\\{ matrix\\.shard \\}\\}/${shards.length}\\n`));
    assert.match(stepBlock(JOB, 'Run Playwright tests'), /npx playwright test --shard="\$E2E_SHARD"\n/);
  });

  it('gives each shard its own concurrency group, compose project, cerbos tag and artifact', () => {
    assert.match(JOB.match(/\n {6}group: ([^\n]+)/)![1], SHARD);
    assert.match(JOB.match(/\n {6}COMPOSE_PROJECT_NAME: ([^\n]+)/)![1], SHARD);
    assert.match(JOB.match(/\n {6}CERBOS_CI_TAG: ([^\n]+)/)![1], SHARD);
    assert.match(stepBlock(JOB, 'Upload e2e artifacts').match(/\n {10}name: ([^\n]+)/)![1], SHARD);
  });

  it('regenerates visual baselines in shard 1 only', () => {
    assert.match(stepBlock(JOB, 'Regenerate visual baselines'), /\n {10}matrix\.shard == 1\n {10}&& \(/);
  });

  it('stays a quality-gate dependency', () => {
    const gate = jobBlock(WORKFLOW, 'quality-gate');
    assert.match(gate, /\n {4}needs: \[[^\]]*\be2e\b[^\]]*\]/);
    assert.match(gate, /e2e\)\s+result="\$\{\{ needs\.e2e\.result \}\}"/);
  });
});
