// node --test ci/workflow-fork-guard.test.ts   (Node >= 24; no dependencies)
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import {
  FORK_GUARD,
  checkDirectory,
  checkWorkflow,
  hasForkGuard,
  parseWorkflow,
  selfHostedLabels,
} from './workflow-fork-guard.ts';

const ROOT = join(import.meta.dirname, '..');
const WORKFLOWS = join(ROOT, '.github/workflows');
const ACTIONLINT = join(ROOT, '.github/actionlint.yaml');
const FIXTURES = join(import.meta.dirname, 'fixtures/workflow-fork-guard');
const SCRIPT = join(import.meta.dirname, 'workflow-fork-guard.ts');
const LABELS = selfHostedLabels(readFileSync(ACTIONLINT, 'utf8'));

const fixture = (name: string): string[] =>
  checkWorkflow(name, readFileSync(join(FIXTURES, name), 'utf8'), LABELS);

const run = (...args: string[]) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });

describe('the real workflows', () => {
  it('pass', () => {
    assert.deepEqual(checkDirectory(WORKFLOWS, ACTIONLINT), []);
    const result = run();
    assert.equal(result.status, 0, result.stderr);
  });

  it('guard every ci.yml job, quality-gate included', () => {
    const ci = parseWorkflow(readFileSync(join(WORKFLOWS, 'ci.yml'), 'utf8'));
    assert.ok(ci.triggers.includes('pull_request'));
    assert.ok(ci.jobs.length >= 19, `parsed ${ci.jobs.length} jobs`);
    for (const job of ci.jobs) assert.ok(hasForkGuard(job.if), `ci.yml job ${job.name}: if: ${job.if}`);
    assert.match(ci.jobs.find((j) => j.name === 'quality-gate')!.if!, /^always\(\) && \(/);
  });

  it('read the self-hosted, non-PR workflows as not PR-reachable', () => {
    for (const name of ['build-and-publish-containers.yml', 'dora-metrics.yml']) {
      const workflow = parseWorkflow(readFileSync(join(WORKFLOWS, name), 'utf8'));
      assert.ok(workflow.jobs.length > 0, name);
      assert.ok(!workflow.triggers.some((t) => t.startsWith('pull_request')), `${name}: ${workflow.triggers}`);
    }
  });
});

describe('fixtures', () => {
  it('fail a self-hosted job without the guard, naming workflow and job', () => {
    assert.deepEqual(fixture('self-hosted-unguarded.yml'), [
      'self-hosted-unguarded.yml: job build runs on a self-hosted runner (runs-on: k8s-runner) on a pull request without the fork guard in its if:',
    ]);
  });

  it('fail a matrix runner with a self-hosted value without the guard', () => {
    const problems = fixture('matrix-unguarded.yml');
    assert.equal(problems.length, 1);
    assert.match(problems[0], /^matrix-unguarded\.yml: job test runs on a self-hosted runner/);
  });

  it('fail any use of pull_request_target', () => {
    const problems = fixture('pull-request-target.yml');
    assert.equal(problems.length, 1);
    assert.match(problems[0], /^pull-request-target\.yml: uses pull_request_target/);
  });

  it('exit non-zero from the CLI, naming each workflow and job', () => {
    const result = run(FIXTURES, ACTIONLINT);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /self-hosted-unguarded\.yml: job build /);
    assert.match(result.stderr, /matrix-unguarded\.yml: job test /);
    assert.match(result.stderr, /pull-request-target\.yml: uses pull_request_target/);
  });

  it('pass once the guard is added', () => {
    const text = readFileSync(join(FIXTURES, 'self-hosted-unguarded.yml'), 'utf8').replace(
      "if: needs.changes.outputs.web == 'true'",
      `if: (needs.changes.outputs.web == 'true') && (${FORK_GUARD})`,
    );
    assert.deepEqual(checkWorkflow('fixed.yml', text, LABELS), []);
  });

  it('ignore a self-hosted job in a workflow no pull request triggers', () => {
    const text = readFileSync(join(FIXTURES, 'self-hosted-unguarded.yml'), 'utf8').replace(
      /pull_request:\n {4}branches: \[main\]/,
      'push:\n    branches: [main]',
    );
    assert.deepEqual(checkWorkflow('push.yml', text, LABELS), []);
  });
});

describe('hasForkGuard', () => {
  it('accepts the guard alone, wrapped, or AND-ed at top level', () => {
    assert.ok(hasForkGuard(FORK_GUARD));
    assert.ok(hasForkGuard(`\${{ ${FORK_GUARD} }}`));
    assert.ok(hasForkGuard(`always() && (${FORK_GUARD})`));
    assert.ok(hasForkGuard(`(a == 'x' || b) && (${FORK_GUARD})`));
    assert.ok(hasForkGuard(`(a\n  || b)\n  && (${FORK_GUARD})`));
  });

  it('rejects a missing, OR-ed or nested guard', () => {
    assert.ok(!hasForkGuard(undefined));
    assert.ok(!hasForkGuard('always()'));
    assert.ok(!hasForkGuard(`(${FORK_GUARD}) || always()`));
    assert.ok(!hasForkGuard(`always() && (${FORK_GUARD}) || x`));
    assert.ok(!hasForkGuard(`!(${FORK_GUARD})`));
    assert.ok(!hasForkGuard(`x && (y || (${FORK_GUARD}))`));
  });
});

describe('selfHostedLabels', () => {
  it('reads the actionlint labels plus self-hosted', () => {
    assert.deepEqual(LABELS, ['self-hosted', 'k8s-runner', 'k8s-runner-integration']);
  });
});
